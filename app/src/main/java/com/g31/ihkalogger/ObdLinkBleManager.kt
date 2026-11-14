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
                connectDeferred?.completeExceptionally(RuntimeException("GATT disconnected (status=$status)"))
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
                connectDeferred?.completeExceptionally(RuntimeException("FFF0/FFF1/FFF2 UART profile missing"))
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
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    connectDeferred?.complete(Unit)
                } else {
                    connectDeferred?.completeExceptionally(RuntimeException("CCCD subscribe failed ($status) - accept BLE bond prompt!"))
                }
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

    suspend fun cmd(command: String, timeoutMs: Long = 2000L): String = cmdMutex.withLock {
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

            val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(wc, slice, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                wc.value = slice
                @Suppress("DEPRECATION")
                g.writeCharacteristic(wc)
            }
            if (!initiated) error("BLE writeCharacteristic rejected")
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

    suspend fun initAdapter(): Pair<String, String> {
        val elm = cmd("ATZ", 3000L)
        cmd("ATE0")
        cmd("ATL0")
        cmd("ATS0")
        cmd("ATH1")
        cmd("ATAT1")
        cmd("ATST32")
        val stn = cmd("STI")
        return elm to stn
    }

    suspend fun setStandardObd() {
        cmd("ATCEA")
        cmd("ATSP6")
        cmd("ATCAF1")
        cmd("ATSH7DF")
        cmd("ATCRA")
    }

    suspend fun setBmwEcu(targetAddr: Int = 0x78) {
        val tgt = "%02X".format(targetAddr)
        val rxId = "%03X".format(0x600 + targetAddr)
        cmd("ATSP6")
        cmd("ATCAF1")
        cmd("ATCERF1")
        val res = cmd("ATCEA$tgt")
        if ("?" in res) error("Adapter rejected ATCEA$tgt: $res")
        cmd("ATSH6F1")
        cmd("ATCRA$rxId")
        cmd("ATFCSH6F1")
        cmd("ATFCSD${tgt}300000")
        cmd("ATFCSM1")
    }

    suspend fun udsRequest(reqHex: String, expectedRxId: String = "678", timeoutMs: Long = 1800L): ByteArray? {
        val raw = cmd(reqHex, timeoutMs)
        val parsed = parseIsoTp(raw, expectedRxId) ?: return null
        if (parsed.isEmpty() || (parsed[0].toInt() and 0xFF) == 0x7F) return null
        return parsed
    }

    fun parseIsoTp(rawResp: String, expectedRxId: String = "678"): ByteArray? {
        if (rawResp.isBlank()) return null
        val upper = rawResp.uppercase()
        if (listOf("NO DATA", "ERROR", "UNABLE", "?", "CAN ERROR").any { it in upper }) return null

        val frames = mutableListOf<ByteArray>()
        for (line in upper.lineSequence()) {
            val clean = line.replace(Regex("[^0-9A-F]"), "")
            if (!clean.startsWith(expectedRxId) || clean.length < 7) continue
            if (clean.substring(3, 5) != "F1") continue
            val payloadHex = clean.substring(5)
            if (payloadHex.length % 2 == 0) {
                frames.add(payloadHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }
        }
        if (frames.isEmpty()) return null

        val first = frames[0]
        val pciType = (first[0].toInt() ushr 4) and 0x0F
        return when (pciType) {
            0x0 -> {
                val len = first[0].toInt() and 0x0F
                if (first.size >= 1 + len) first.copyOfRange(1, 1 + len) else null
            }
            0x1 -> {
                val totalLen = ((first[0].toInt() and 0x0F) shl 8) or (first[1].toInt() and 0xFF)
                val out = ArrayList<Byte>(totalLen)
                for (i in 2 until first.size) out.add(first[i])
                for (cf in frames.drop(1)) {
                    if (cf.isNotEmpty() && ((cf[0].toInt() ushr 4) and 0x0F) == 0x2) {
                        for (i in 1 until cf.size) out.add(cf[i])
                    }
                }
                out.take(totalLen).toByteArray()
            }
            else -> null
        }
    }

    fun disconnect() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        _connectionState.value = "Disconnected"
    }
}
