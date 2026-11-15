package com.g31.ihkalogger

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.resume

@SuppressLint("MissingPermission")
class ObdLinkBleManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID  = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID   = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter: BluetoothAdapter? get() = btManager.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23

    private val cmdMutex = Mutex()
    private val rxBuffer = StringBuilder()

    @Volatile private var connectDeferred: CompletableDeferred<Unit>? = null
    @Volatile private var writeAckDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var promptDeferred: CompletableDeferred<String>? = null

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    val isConnected: Boolean get() = gatt != null && writeChar != null

    suspend fun scanAndConnect(timeoutMs: Long = 10_000L) {
        val adapter = btAdapter ?: error("Bluetooth adapter unavailable")
        if (!adapter.isEnabled) error("Please turn on Bluetooth first")

        disconnect()
        _connectionState.value = "Scanning for OBDLink CX..."

        val device = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<BluetoothDevice> { cont ->
                val scanner = adapter.bluetoothLeScanner ?: error("BLE Scanner unavailable")
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val name = result.device.name ?: result.scanRecord?.deviceName ?: ""
                        val hasUuid = result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true
                        if (name.contains("OBDLink", ignoreCase = true) || hasUuid) {
                            scanner.stopScan(this)
                            if (cont.isActive) cont.resume(result.device)
                        }
                    }
                    override fun onScanFailed(errorCode: Int) {
                        if (cont.isActive) cont.cancel(RuntimeException("BLE scan error: $errorCode"))
                    }
                }
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(null, settings, callback)
                cont.invokeOnCancellation { runCatching { scanner.stopScan(callback) } }
            }
        } ?: error("OBDLink CX not found! Unplug & replug CX to open 5-min pairing window.")

        _connectionState.value = "Connecting GATT (${device.address})..."
        val ready = CompletableDeferred<Unit>()
        connectDeferred = ready
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        withTimeout(12_000L) { ready.await() }
        _connectionState.value = "Connected (MTU $negotiatedMtu)"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = "Disconnected"
                writeChar = null
                connectDeferred?.completeExceptionally(RuntimeException("GATT disconnected ($status)"))
                promptDeferred?.completeExceptionally(RuntimeException("BLE link dropped"))
                g.close()
                if (gatt === g) gatt = null
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectDeferred?.completeExceptionally(RuntimeException("Service discovery failed: $status"))
                return
            }
            val svc = g.getService(SERVICE_UUID)
            val notifyC = svc?.getCharacteristic(NOTIFY_UUID)
            val writeC = svc?.getCharacteristic(WRITE_UUID)
            if (notifyC == null || writeC == null) {
                connectDeferred?.completeExceptionally(RuntimeException("FFF0/FFF1/FFF2 missing"))
                return
            }
            writeChar = writeC
            g.setCharacteristicNotification(notifyC, true)

            val cccd = notifyC.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            } else {
                connectDeferred?.complete(Unit)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) connectDeferred?.complete(Unit)
                else connectDeferred?.completeExceptionally(RuntimeException("CCCD failed ($status)"))
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeAckDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncomingBytes(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                handleIncomingBytes(characteristic.value ?: return)
            }
        }
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        val chunk = bytes.toString(Charsets.US_ASCII)
        synchronized(rxBuffer) {
            rxBuffer.append(chunk)
            if (rxBuffer.contains(">")) {
                promptDeferred?.complete(rxBuffer.toString())
            }
        }
    }

    suspend fun cmd(command: String, timeoutMs: Long = 1500L): String = cmdMutex.withLock {
        val g = gatt ?: error("Not connected")
        val wc = writeChar ?: error("Write characteristic unavailable")

        synchronized(rxBuffer) { rxBuffer.clear() }
        val pDef = CompletableDeferred<String>()
        promptDeferred = pDef

        val payload = (command.trim() + "\r").toByteArray(Charsets.US_ASCII)
        val maxChunk = maxOf(20, negotiatedMtu - 3)

        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + maxChunk, payload.size)
            val slice = payload.copyOfRange(offset, end)
            val wAck = CompletableDeferred<Boolean>()
            writeAckDeferred = wAck

            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(wc, slice, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                wc.value = slice
                @Suppress("DEPRECATION")
                g.writeCharacteristic(wc)
            }
            if (!ok) error("BLE write rejected")
            withTimeout(1500L) { wAck.await() }
            offset = end
        }

        val raw = withTimeoutOrNull(timeoutMs) { pDef.await() }
            ?: synchronized(rxBuffer) { rxBuffer.toString() }

        raw.replace("\r", "\n")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != command.trim() && it != ">" }
            .joinToString("\n")
    }

    suspend fun initBmwRawCan(): String {
        cmd("ATD", 2000L)
        cmd("ATE0")
        cmd("ATSH6F1")
        cmd("ATCF600")
        cmd("ATCM700")
        cmd("ATPBC001")
        cmd("ATSPB")
        cmd("ATAT0")
        cmd("ATST32")
        cmd("ATAL")
        cmd("ATH1")
        cmd("ATS0")
        cmd("ATL0")
        cmd("ATCSM0")
        cmd("ATJE")
        cmd("ATCAF0")
        return cmd("STI")
    }

    /**
     * Reads 12V OBD pin-16 supply voltage directly from the OBDLink CX ADC via `ATRV`
     * without disturbing the active `ATSPB` / `0x6F1` raw CAN configuration.
     */
    suspend fun readBatteryVoltage(): Float {
        val raw = runCatching { cmd("ATRV", 600L) }.getOrNull() ?: return Float.NaN
        val m = Regex("([0-9]+(?:[.,][0-9]+)?)").find(raw) ?: return Float.NaN
        return m.groupValues[1].replace(',', '.').toFloatOrNull() ?: Float.NaN
    }

    suspend fun bmwUdsRequest(udsHex: String, ecuAddr: Int = 0x78): ByteArray? {
        val cleanUds = udsHex.uppercase().replace(Regex("[^0-9A-F]"), "")
        val byteLen = cleanUds.length / 2
        if (byteLen !in 1..6) return null

        val tgt = "%02X".format(ecuAddr)
        val rxPrefix = "%03XF1".format(0x600 + ecuAddr)
        val sfFrame = ("$tgt%02X$cleanUds".format(byteLen)).padEnd(16, '0')
        val reqSid = cleanUds.substring(0, 2).toInt(16)

        var lines = parseHexLines(cmd(sfFrame, 1200L), rxPrefix)
        if (lines.isEmpty()) return null

        // NRC 0x78 (responsePending): the ECU is still working and will send the real reply later
        // (up to P2* = 5 s). The adapter only listens for replies right after it sends something, so
        // keep the listen window open with silent TesterPresent (3E 80 = no positive response).
        var pendingPolls = 0
        while (true) {
            val idx = lines.indexOfFirst { !isResponsePending(it, reqSid) }
            if (idx >= 0) {
                lines = lines.subList(idx, lines.size)
                break
            }
            if (++pendingPolls > 25) return null
            lines = parseHexLines(cmd("${tgt}023E800000000000", 1200L), rxPrefix)
        }

        val firstPayload = lines[0]
        val pciType = (firstPayload[0].toInt() ushr 4) and 0x0F

        if (pciType == 0x0) {
            val len = firstPayload[0].toInt() and 0x0F
            if (firstPayload.size < 1 + len) return null
            val udsResp = firstPayload.copyOfRange(1, 1 + len)
            return if ((udsResp[0].toInt() and 0xFF) == 0x7F) null else udsResp
        }

        if (pciType == 0x1) {
            val totalLen = ((firstPayload[0].toInt() and 0x0F) shl 8) or (firstPayload[1].toInt() and 0xFF)
            val data = ArrayList<Byte>(totalLen)
            for (i in 2 until firstPayload.size) data.add(firstPayload[i])

            while (data.size < totalLen) {
                val fcRaw = cmd("${tgt}30030000000000", 1200L)
                val cfList = parseHexLines(fcRaw, rxPrefix)
                if (cfList.isEmpty()) break
                for (cf in cfList) {
                    if (cf.isNotEmpty() && ((cf[0].toInt() ushr 4) and 0x0F) == 0x2) {
                        for (i in 1 until cf.size) {
                            if (data.size < totalLen) data.add(cf[i])
                        }
                    }
                }
            }
            val udsResp = data.toByteArray()
            return if (udsResp.isEmpty() || (udsResp[0].toInt() and 0xFF) == 0x7F) null else udsResp
        }
        return null
    }

    /** Single frame `03 7F <sid> 78` = negative response "requestCorrectlyReceived-ResponsePending". */
    private fun isResponsePending(frame: ByteArray, reqSid: Int): Boolean =
        frame.size >= 4 &&
            (frame[0].toInt() and 0xFF) == 0x03 &&
            (frame[1].toInt() and 0xFF) == 0x7F &&
            (frame[2].toInt() and 0xFF) == reqSid &&
            (frame[3].toInt() and 0xFF) == 0x78

    private fun parseHexLines(raw: String, prefix: String): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        for (line in raw.uppercase().lineSequence()) {
            val clean = line.replace(Regex("[^0-9A-F]"), "")
            if (clean.startsWith(prefix) && clean.length >= prefix.length + 2) {
                val body = clean.substring(prefix.length)
                if (body.length % 2 == 0) {
                    out.add(body.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                }
            }
        }
        return out
    }

    fun disconnect() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        _connectionState.value = "Disconnected"
    }
}
