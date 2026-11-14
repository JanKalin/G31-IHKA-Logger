package com.g31.ihkalogger

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DidReading(
    val didHex: String,
    val rawHex: String,
    val decodedPrimary: String
)

data class UiState(
    val connStatus: String = "Disconnected",
    val isBusy: Boolean = false,
    val isLogging: Boolean = false,
    val didInput: String = "D901 D904 D905 D910",
    val batteryV: Float? = null,
    val coolantC: Int? = null,
    val ambientC: Int? = null,
    val rpm: Int? = null,
    val sampleCount: Int = 0,
    val activeCsvName: String = "",
    val didReadings: List<DidReading> = emptyList(),
    val sparkCoolant: List<Float> = emptyList(),
    val sparkPrimaryTemp: List<Float> = emptyList(),
    val consoleLines: List<String> = emptyList()
)

class IhkaViewModel(private val app: Application) : AndroidViewModel(app) {

    private val ble = ObdLinkBleManager(app.applicationContext)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var activeJob: Job? = null
    @Volatile private var pendingMarker: String = ""
    private var latestCsvFile: File? = null

    init {
        viewModelScope.launch {
            ble.connectionState.collect { st ->
                _ui.value = if (st == "Disconnected") {
                    _ui.value.copy(connStatus = st, batteryV = null)
                } else {
                    _ui.value.copy(connStatus = st)
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(3000L)
                if (ble.isConnected && !_ui.value.isBusy && !_ui.value.isLogging) {
                    runCatching { readBatteryVoltage() }.getOrNull()?.let { v ->
                        _ui.value = _ui.value.copy(batteryV = v)
                    }
                }
            }
        }
    }

    fun updateDidInput(text: String) {
        _ui.value = _ui.value.copy(didInput = text)
    }

    fun flagCabinEvent(tag: String) {
        pendingMarker = tag
        appendLog("[MARKER] Queued '$tag' for next 1 Hz CSV row")
    }

    private fun appendLog(msg: String) {
        val cur = _ui.value.consoleLines
        val updated = (listOf(msg) + cur).take(120)
        _ui.value = _ui.value.copy(consoleLines = updated)
    }

    fun connectAdapter() {
        if (_ui.value.isBusy || _ui.value.isLogging) return
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(isBusy = true)
            try {
                ble.scanAndConnect()
                val (elm, stn) = ble.initAdapter()
                appendLog("[+] Ready: $elm | $stn")
                val battV = readBatteryVoltage()
                val obd = readStandardObd()
                _ui.value = _ui.value.copy(
                    batteryV = battV,
                    coolantC = obd.first,
                    ambientC = obd.second,
                    rpm = obd.third
                )
                val vStr = battV?.let { String.format(Locale.US, "%.1fV", it) } ?: "?"
                appendLog("[+] Batt=$vStr | B58 Coolant=${obd.first ?: "?"}°C | Amb=${obd.second ?: "?"}°C | RPM=${obd.third ?: "?"}")
            } catch (e: Exception) {
                appendLog("[!] Connect error: ${e.message}")
            } finally {
                _ui.value = _ui.value.copy(isBusy = false)
            }
        }
    }

    fun readIhkaInfoAndDtcs() {
        if (!ble.isConnected || _ui.value.isBusy || _ui.value.isLogging) {
            appendLog("[!] Connect to OBDLink CX first.")
            return
        }
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(isBusy = true)
            try {
                ble.setBmwEcu(0x78)
                val sess = ble.udsRequest("1003")
                if (sess == null || (sess[0].toInt() and 0xFF) != 0x50) {
                    appendLog("[!] No reply from IHKA (0x78). Press Start 3x quickly for Diagnostic Mode!")
                    return@launch
                }
                appendLog("[+] IHKA Session 1003 active")

                val part = ble.udsRequest("22F187")
                if (part != null && part.size > 3 && (part[0].toInt() and 0xFF) == 0x62) {
                    val pn = part.copyOfRange(3, part.size).toString(Charsets.US_ASCII).trim()
                    appendLog("[+] IHKA BMW Part #: $pn")
                }

                val dtc = ble.udsRequest("19020C", timeoutMs = 3000L)
                if (dtc != null && dtc.size >= 3 && (dtc[0].toInt() and 0xFF) == 0x59) {
                    val body = dtc.copyOfRange(3, dtc.size)
                    if (body.isEmpty()) {
                        appendLog("[+] Zero active/stored fault codes in IHKA.")
                    } else {
                        val count = body.size / 4
                        appendLog("[!] Found $count IHKA Fault Code(s):")
                        var i = 0
                        while (i + 3 < body.size) {
                            val code = "%02X%02X%02X".format(body[i], body[i + 1], body[i + 2])
                            val status = "%02X".format(body[i + 3])
                            appendLog("    DTC 0x$code (status=0x$status)")
                            i += 4
                        }
                    }
                }
            } catch (e: Exception) {
                appendLog("[!] Info error: ${e.message}")
            } finally {
                _ui.value = _ui.value.copy(isBusy = false)
            }
        }
    }

    fun sweepIhkaDids() {
        if (!ble.isConnected || _ui.value.isBusy || _ui.value.isLogging) {
            appendLog("[!] Connect to OBDLink CX first.")
            return
        }
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(isBusy = true)
            val hits = mutableListOf<String>()
            try {
                ble.setBmwEcu(0x78)
                ble.cmd("ATST19")
                ble.udsRequest("1003")
                val ranges = listOf(0xD800..0xDBFF, 0x4000..0x41FF)
                appendLog("[*] Sweeping IHKA DIDs (tap STOP to abort early)...")

                for (r in ranges) {
                    for (did in r) {
                        ensureActive()
                        if ((did - r.first) % 64 == 0 && did > r.first) {
                            ble.udsRequest("3E00", timeoutMs = 500L)
                            readBatteryVoltage()?.let { v ->
                                _ui.value = _ui.value.copy(batteryV = v)
                            }
                        }
                        val hexId = "%04X".format(did)
                        val resp = ble.udsRequest("22$hexId", timeoutMs = 550L)
                        if (resp != null && resp.size > 3 && (resp[0].toInt() and 0xFF) == 0x62) {
                            val vb = resp.copyOfRange(3, resp.size)
                            val hx = vb.joinToString("") { "%02X".format(it) }
                            val hint = decodeHint(vb)
                            hits.add(hexId)
                            appendLog("[HIT] 0x$hexId = $hx ($hint)")
                        }
                    }
                }
                ble.cmd("ATST32")
                appendLog("[+] Sweep done: ${hits.size} DIDs found.")
                if (hits.isNotEmpty()) {
                    _ui.value = _ui.value.copy(didInput = hits.take(8).joinToString(" "))
                }
            } catch (_: CancellationException) {
                appendLog("[*] Sweep stopped (${hits.size} hits so far).")
                if (hits.isNotEmpty()) {
                    _ui.value = _ui.value.copy(didInput = hits.take(8).joinToString(" "))
                }
            } catch (e: Exception) {
                appendLog("[!] Sweep error: ${e.message}")
            } finally {
                runCatching { ble.cmd("ATST32") }
                _ui.value = _ui.value.copy(isBusy = false)
            }
        }
    }

    fun startLogging() {
        if (!ble.isConnected || _ui.value.isLogging) {
            appendLog("[!] Connect to OBDLink CX before logging.")
            return
        }
        val dids = _ui.value.didInput
            .trim()
            .split(Regex("[\\s,]+"))
            .map { it.uppercase().removePrefix("0X") }
            .filter { it.matches(Regex("^[0-9A-F]{4}$")) }

        if (dids.isEmpty()) {
            appendLog("[!] Enter at least one 4-digit hex DID (or run SWEEP first).")
            return
        }

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val csvFile = File(app.getExternalFilesDir(null), "g31_ihka_ble_log_$stamp.csv")
        latestCsvFile = csvFile

        activeJob = viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(
                isLogging = true,
                sampleCount = 0,
                activeCsvName = csvFile.name,
                sparkCoolant = emptyList(),
                sparkPrimaryTemp = emptyList()
            )
            appendLog("[*] Logging to ${csvFile.name}")

            val headers = mutableListOf(
                "timestamp", "elapsed_s", "battery_v", "b58_coolant_c", "ambient_obd_c", "b58_rpm", "event_marker"
            )
            for (d in dids) {
                headers.addAll(
                    listOf("DID_${d}_hex", "DID_${d}_1b_m40", "DID_${d}_u16_01_m40", "DID_${d}_s16_01")
                )
            }

            val t0 = System.nanoTime()
            try {
                csvFile.bufferedWriter().use { writer ->
                    writer.write(headers.joinToString(",") + "\n")
                    writer.flush()

                    var count = 0
                    while (isActive) {
                        val loopStart = System.nanoTime()
                        val elapsedS = (loopStart - t0) / 1_000_000_000.0
                        val isoNow = LocalDateTime.now().toString()
                        val marker = pendingMarker.also { pendingMarker = "" }

                        val battV = readBatteryVoltage()
                        val (coolant, amb, rpm) = readStandardObd()
                        ble.setBmwEcu(0x78)
                        ble.udsRequest("3E00", timeoutMs = 500L)

                        val row = mutableListOf(
                            isoNow,
                            String.format(Locale.US, "%.2f", elapsedS),
                            battV?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                            coolant?.toString() ?: "",
                            amb?.toString() ?: "",
                            rpm?.toString() ?: "",
                            marker
                        )

                        val liveCards = mutableListOf<DidReading>()
                        var firstPlausibleTemp: Float? = null

                        for (d in dids) {
                            val resp = ble.udsRequest("22$d", timeoutMs = 700L)
                            if (resp != null && resp.size > 3 && (resp[0].toInt() and 0xFF) == 0x62) {
                                val vb = resp.copyOfRange(3, resp.size)
                                val hx = vb.joinToString("") { "%02X".format(it) }
                                val b0 = vb[0].toInt() and 0xFF
                                val c1b = String.format(Locale.US, "%.1f", b0 - 40.0)

                                var cU16 = ""
                                var cS16 = ""
                                if (vb.size >= 2) {
                                    val u16 = ((vb[0].toInt() and 0xFF) shl 8) or (vb[1].toInt() and 0xFF)
                                    val s16 = ((vb[0].toInt() shl 8) or (vb[1].toInt() and 0xFF)).toShort().toInt()
                                    val uVal = 0.1 * u16 - 40.0
                                    cU16 = String.format(Locale.US, "%.2f", uVal)
                                    cS16 = String.format(Locale.US, "%.2f", 0.1 * s16)
                                    if (firstPlausibleTemp == null && uVal in -25.0..90.0) {
                                        firstPlausibleTemp = uVal.toFloat()
                                    }
                                } else if (firstPlausibleTemp == null && (b0 - 40) in -25..85) {
                                    firstPlausibleTemp = (b0 - 40).toFloat()
                                }

                                row.addAll(listOf(hx, c1b, cU16, cS16))
                                liveCards.add(DidReading(d, hx, decodeHint(vb)))
                            } else {
                                row.addAll(listOf("", "", "", ""))
                                liveCards.add(DidReading(d, "ERR", "--"))
                            }
                        }

                        writer.write(row.joinToString(",") + "\n")
                        writer.flush()
                        count++

                        val cur = _ui.value
                        val nextCoolantSpark = if (coolant != null) (cur.sparkCoolant + coolant.toFloat()).takeLast(120) else cur.sparkCoolant
                        val nextIhkaSpark = if (firstPlausibleTemp != null) (cur.sparkPrimaryTemp + firstPlausibleTemp).takeLast(120) else cur.sparkPrimaryTemp

                        _ui.value = cur.copy(
                            batteryV = battV ?: cur.batteryV,
                            coolantC = coolant,
                            ambientC = amb,
                            rpm = rpm,
                            sampleCount = count,
                            didReadings = liveCards,
                            sparkCoolant = nextCoolantSpark,
                            sparkPrimaryTemp = nextIhkaSpark
                        )

                        val elapsedLoopMs = (System.nanoTime() - loopStart) / 1_000_000L
                        val sleepMs = 1000L - elapsedLoopMs
                        if (sleepMs > 20L) delay(sleepMs)
                    }
                }
            } catch (_: CancellationException) {
                appendLog("[+] Saved ${csvFile.name} (${_ui.value.sampleCount} rows)")
            } catch (e: Exception) {
                appendLog("[!] Logging stopped: ${e.message}")
            } finally {
                _ui.value = _ui.value.copy(isLogging = false)
            }
        }
    }

    fun stopActiveTask() {
        activeJob?.cancel()
    }

    fun disconnectBle() {
        activeJob?.cancel()
        ble.disconnect()
    }

    fun getLatestCsvFile(): File? {
        latestCsvFile?.takeIf { it.exists() }?.let { return it }
        return app.getExternalFilesDir(null)
            ?.listFiles { f -> f.name.endsWith(".csv") }
            ?.maxByOrNull { it.lastModified() }
    }

    private suspend fun readBatteryVoltage(): Float? {
        val raw = ble.cmd("ATRV", 800L)
        val match = Regex("""(\d{1,2}(?:\.\d{1,2})?)\s*V?""", RegexOption.IGNORE_CASE).find(raw)
        return match?.groupValues?.getOrNull(1)?.toFloatOrNull()?.takeIf { it in 5.0f..20.0f }
    }

    private suspend fun readStandardObd(): Triple<Int?, Int?, Int?> {
        ble.setStandardObd()
        var coolant: Int? = null
        var amb: Int? = null
        var rpm: Int? = null

        val r05 = ble.cmd("0105", 1200L)
        parseObdByte(r05, "4105")?.let { coolant = it - 40 }

        val r46 = ble.cmd("0146", 1200L)
        parseObdByte(r46, "4146")?.let { amb = it - 40 }

        val r0C = ble.cmd("010C", 1200L)
        parseObdWord(r0C, "410C")?.let { rpm = it / 4 }

        return Triple(coolant, amb, rpm)
    }

    private fun parseObdByte(raw: String, marker: String): Int? {
        for (line in raw.lineSequence()) {
            val clean = line.uppercase().replace(Regex("[^0-9A-F]"), "")
            val idx = clean.indexOf(marker)
            if (idx != -1 && clean.length >= idx + 6) {
                return clean.substring(idx + 4, idx + 6).toIntOrNull(16)
            }
        }
        return null
    }

    private fun parseObdWord(raw: String, marker: String): Int? {
        for (line in raw.lineSequence()) {
            val clean = line.uppercase().replace(Regex("[^0-9A-F]"), "")
            val idx = clean.indexOf(marker)
            if (idx != -1 && clean.length >= idx + 8) {
                return clean.substring(idx + 4, idx + 8).toIntOrNull(16)
            }
        }
        return null
    }

    private fun decodeHint(vb: ByteArray): String {
        if (vb.isEmpty()) return ""
        return if (vb.size == 1) {
            val b0 = vb[0].toInt() and 0xFF
            if (b0 in 0..100) "${b0}% | ${b0 - 40}°C" else "${b0 - 40}°C"
        } else {
            val u16 = ((vb[0].toInt() and 0xFF) shl 8) or (vb[1].toInt() and 0xFF)
            String.format(Locale.US, "%.1f°C", 0.1 * u16 - 40.0)
        }
    }

    override fun onCleared() {
        super.onCleared()
        ble.disconnect()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101418)) {
                    IhkaCockpitScreen()
                }
            }
        }
    }
}

@Composable
fun IhkaCockpitScreen(vm: IhkaViewModel = viewModel()) {
    val state by vm.ui.collectAsState()
    val context = LocalContext.current

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) vm.connectAdapter()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("BMW G31 540i · IHKA UDS", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(state.connStatus, fontSize = 12.sp, color = Color(0xFF64B5F6))
            }
            Button(
                onClick = {
                    if (state.connStatus.startsWith("Connected")) {
                        vm.disconnectBle()
                    } else {
                        permLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.connStatus.startsWith("Connected")) Color(0xFF455A64) else Color(0xFF1976D2)
                )
            ) {
                Text(if (state.connStatus.startsWith("Connected")) "Disconnect" else "Connect CX")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val battColor = when {
                state.batteryV == null -> Color(0xFFCE93D8)
                state.batteryV!! < 11.8f -> Color(0xFFEF5350)
                state.batteryV!! < 12.2f -> Color(0xFFFFCA28)
                else -> Color(0xFF66BB6A)
            }
            MetricTile("Battery", state.batteryV?.let { String.format(Locale.US, "%.1f V", it) } ?: "--", battColor, Modifier.weight(1f))
            MetricTile("Coolant", state.coolantC?.let { "$it °C" } ?: "--", Color(0xFFEF5350), Modifier.weight(1f))
            MetricTile("Ambient", state.ambientC?.let { "$it °C" } ?: "--", Color(0xFF4FC3F7), Modifier.weight(1f))
            MetricTile("B58 RPM", state.rpm?.toString() ?: "--", Color(0xFFFFB74D), Modifier.weight(1f))
            MetricTile("Rows", state.sampleCount.toString(), Color(0xFF81C784), Modifier.weight(0.75f))
        }

        OutlinedTextField(
            value = state.didInput,
            onValueChange = { vm.updateDidInput(it) },
            enabled = !state.isLogging && !state.isBusy,
            singleLine = true,
            label = { Text("IHKA Hex DIDs (space-separated, or tap SWEEP)") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = { vm.readIhkaInfoAndDtcs() },
                enabled = !state.isBusy && !state.isLogging,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
            ) { Text("DTCs", fontSize = 12.sp) }

            OutlinedButton(
                onClick = { if (state.isBusy) vm.stopActiveTask() else vm.sweepIhkaDids() },
                enabled = !state.isLogging,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
            ) { Text(if (state.isBusy) "STOP" else "SWEEP", fontSize = 12.sp) }

            Button(
                onClick = { if (state.isLogging) vm.stopActiveTask() else vm.startLogging() },
                enabled = !state.isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isLogging) Color(0xFFD32F2F) else Color(0xFF2E7D32)
                ),
                modifier = Modifier.weight(1.3f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
            ) { Text(if (state.isLogging) "STOP LOG" else "START LOG", fontSize = 12.sp, fontWeight = FontWeight.Bold) }

            OutlinedButton(
                onClick = {
                    val file = vm.getLatestCsvFile()
                    if (file != null) {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "Export ${file.name}"))
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
            ) { Text("SHARE", fontSize = 12.sp) }
        }

        if (state.isLogging) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { vm.flagCabinEvent("HOT_SWING") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("MARK HOT BLAST", fontWeight = FontWeight.Bold) }

                Button(
                    onClick = { vm.flagCabinEvent("COLD_SWING") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0277BD)),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("MARK COLD BLAST", fontWeight = FontWeight.Bold) }
            }
        }

        SparklineBox(
            coolant = state.sparkCoolant,
            ihkaTemp = state.sparkPrimaryTemp,
            modifier = Modifier.fillMaxWidth().height(95.dp)
        )

        if (state.didReadings.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (card in state.didReadings.take(4)) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0xFF1C2229), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text("0x${card.didHex}", fontSize = 11.sp, color = Color(0xFF90CAF9), fontFamily = FontFamily.Monospace)
                        Text(card.decodedPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(card.rawHex, fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF0A0D10), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            items(state.consoleLines) { ln ->
                Text(
                    text = ln,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        ln.startsWith("[!]") -> Color(0xFFEF9A9A)
                        ln.startsWith("[HIT]") || ln.startsWith("[+]") -> Color(0xFFA5D6A7)
                        ln.startsWith("[MARKER]") -> Color(0xFFFFCC80)
                        else -> Color(0xFFB0BEC5)
                    }
                )
            }
        }
    }
}

@Composable
fun MetricTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFF1A2027), RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp, horizontal = 8.dp)
    ) {
        Text(label, fontSize = 11.sp, color = Color(0xFF90A4AE), maxLines = 1)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1)
    }
}

@Composable
fun SparklineBox(coolant: List<Float>, ihkaTemp: List<Float>, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xFF161B21), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        if (coolant.size < 2 && ihkaTemp.size < 2) {
            Text(
                "Live 120s Sparkline (Red = B58 Coolant, Cyan = 1st IHKA Temp DID)",
                fontSize = 11.sp,
                color = Color(0xFF607D8B),
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val all = coolant + ihkaTemp
                val minV = (all.minOrNull() ?: -10f) - 2f
                val maxV = (all.maxOrNull() ?: 100f) + 2f
                val span = (maxV - minV).coerceAtLeast(1f)

                fun drawTrace(pts: List<Float>, color: Color) {
                    if (pts.size < 2) return
                    val path = Path()
                    pts.forEachIndexed { i, v ->
                        val x = size.width * (i.toFloat() / (pts.size - 1).coerceAtLeast(1))
                        val y = size.height * (1f - (v - minV) / span)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = color, style = Stroke(width = 3f))
                }

                drawTrace(coolant, Color(0xFFEF5350))
                drawTrace(ihkaTemp, Color(0xFF29B6F6))
            }
        }
    }
}
