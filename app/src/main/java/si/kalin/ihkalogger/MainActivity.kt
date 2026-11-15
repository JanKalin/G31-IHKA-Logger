package si.kalin.ihkalogger

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class IhkaSnapshot(
    val batteryV: Float = Float.NaN,
    // DID 0xD8D7: duct/cabin temperatures (rear ducts only populated on 4-zone cars)
    val evapC: Float = Float.NaN,
    val cabinC: Int = 0,
    val ventFrontL: Int = 0,
    val ventFrontR: Int = 0,
    val footFrontL: Int = 0,
    val footFrontR: Int = 0,
    val ventRearL: Int = 0,
    val ventRearR: Int = 0,
    val footRearL: Int = 0,
    val footRearR: Int = 0,
    // DID 0xD8D9: Bus-In Signals
    val outsideC: Float = Float.NaN,
    val windshieldC: Float = Float.NaN,
    val windshieldRh: Float = Float.NaN,
    val acBar: Int? = null,
    val coolantC: Int? = null,
    // DID 0xD8DB: Active Aircon Settings & Blower Steps
    val acOn: Int = -1,
    val maxAcOn: Int = -1,
    val autoFlapL: Int = -1,
    val autoFlapR: Int = -1,
    val autoBlowerL: Int = -1,
    val autoBlowerR: Int = -1,
    val aucOn: Int = -1,
    val recircOn: Int = -1,
    val defrostProgOn: Int = -1,
    val systemOff: Int = -1,
    val blowerStepL: Int = -1,
    val blowerStepR: Int = -1,
    val blowerStepRear: Int = -1,
    // DID 0xD8D8: Stratification Potentiometers (0=Cold, 50=Neutral, 100=Warm)
    val stratL: Int = -1,
    val stratR: Int = -1,
    val stratRearL: Int = -1,
    val stratRearR: Int = -1,
    // Routine 0xA12A: Target Setpoint Temperatures (°C) & SYNC state
    val targetFrontL: Int = -1,
    val targetFrontR: Int = -1,
    val targetRearL: Int = -1,
    val targetRearR: Int = -1,
    val syncActive: Int = -1,
    // Routine 0xA128: Actual Blower Power Output (0..100%)
    val blowerPctFront: Int = -1,
    val blowerPctRear: Int = -1,
    // Routine 0xA12B: Electric PTC Auxiliary Heater Duty Cycle (%) & PCB Temp (°C)
    val ptcDutyPct: Float = Float.NaN,
    val ptcTempC: Int = -999,
    val ptcCurrentA: Float = Float.NaN,
    // Routine 0xA122: LIN Flap Positions (0..100%) — Front & Rear
    val flapDefrost: Int = -1,
    val flapVentL: Int = -1,
    val flapVentR: Int = -1,
    val flapFootL: Int = -1,
    val flapFootR: Int = -1,
    val flapRearDistL: Int = -1,
    val flapRearDistR: Int = -1,
    val flapMixL: Int = -1,
    val flapMixR: Int = -1,
    val flapRearMixL: Int = -1,
    val flapRearMixR: Int = -1
) {
    fun ventStateSummary(): String {
        if (systemOff == 1) return "OFF"
        if (defrostProgOn == 1) return "MAX DEF"
        val lMode = when (autoFlapL) {
            1 -> "AUTO"
            0 -> "MAN"
            else -> "--"
        }
        val rMode = when (autoFlapR) {
            1 -> "AUTO"
            0 -> "MAN"
            else -> "--"
        }
        val acTag = if (maxAcOn == 1) "MAX-AC" else if (acOn == 1) "AC:ON" else if (acOn == 0) "AC:OFF" else ""
        return if (lMode == rMode) "$lMode $acTag".trim() else "L:$lMode/R:$rMode $acTag".trim()
    }
}

enum class ZoneMode(val label: String) { AUTO("Auto"), TWO_ZONE("2-Zone"), FOUR_ZONE("4-Zone") }

data class UiState(
    val zoneMode: ZoneMode = ZoneMode.AUTO,
    /** Result of auto-detection from the rear duct bytes of 0xD8D7; null until decided. */
    val detectedZones: Int? = null,
    val connStatus: String = "Disconnected",
    val isBusy: Boolean = false,
    val isLogging: Boolean = false,
    val sampleCount: Int = 0,
    val snap: IhkaSnapshot = IhkaSnapshot(),
    val sparkEvap: List<Float> = emptyList(),
    val sparkFootL: List<Float> = emptyList(),
    val sparkWindshield: List<Float> = emptyList(),
    val consoleLines: List<String> = emptyList()
) {
    /** Zone count actually in effect: manual override wins, else detection, else assume 4. */
    val zones: Int get() = when (zoneMode) {
        ZoneMode.TWO_ZONE -> 2
        ZoneMode.FOUR_ZONE -> 4
        ZoneMode.AUTO -> detectedZones ?: 4
    }
}

class IhkaViewModel(private val app: Application) : AndroidViewModel(app) {

    private val ble = ObdLinkBleManager(app.applicationContext)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var activeJob: Job? = null
    private val pendingMarker = AtomicReference("")
    private val sharePrefs = app.getSharedPreferences("csv_share", Context.MODE_PRIVATE)
    private val settingsPrefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Auto zone detection: one valid rear reading latches 4-zone for the session;
    // 2-zone needs several consecutive all-unpopulated samples (a real 4-zone duct can read 0x00 = 0°C).
    private var rearSeenValid = false
    private var rearEmptyStreak = 0

    init {
        val saved = settingsPrefs.getString(KEY_ZONE_MODE, null)
        val mode = ZoneMode.entries.firstOrNull { it.name == saved } ?: ZoneMode.AUTO
        _ui.update { it.copy(zoneMode = mode) }

        viewModelScope.launch {
            ble.connectionState.collect { st ->
                _ui.update { it.copy(connStatus = st) }
            }
        }
    }

    fun setZoneMode(mode: ZoneMode) {
        settingsPrefs.edit().putString(KEY_ZONE_MODE, mode.name).apply()
        _ui.update { it.copy(zoneMode = mode) }
        val st = _ui.value
        val detail = if (mode == ZoneMode.AUTO) " (detected: ${st.detectedZones?.let { "$it-zone" } ?: "pending"})" else ""
        appendLog("[*] Zone mode set to ${mode.label}$detail")
    }

    private fun updateZoneDetection(rearRaw: List<Int>) {
        val unpopulated = rearRaw.all { it == 0x80 || it == 0x00 }
        if (!unpopulated) {
            rearSeenValid = true
            rearEmptyStreak = 0
        } else if (!rearSeenValid) {
            rearEmptyStreak++
        }
        val detected = when {
            rearSeenValid -> 4
            rearEmptyStreak >= 3 -> 2
            else -> null
        }
        if (detected != null && detected != _ui.value.detectedZones) {
            _ui.update { it.copy(detectedZones = detected) }
            appendLog("[*] Auto-detected $detected-zone IHKA (rear duct bytes ${rearRaw.joinToString(" ") { "%02X".format(it) }})")
        }
    }

    fun flagCabinEvent(tag: String) {
        pendingMarker.set(tag)
        appendLog("[MARKER] Queued '$tag' for next 1 Hz CSV row")
    }

    private fun appendLog(msg: String) {
        _ui.update { it.copy(consoleLines = (listOf(msg) + it.consoleLines).take(120)) }
    }

    fun connectAdapter() {
        if (_ui.value.isBusy || _ui.value.isLogging) return
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(isBusy = true) }
            try {
                ble.scanAndConnect()
                val stn = ble.initBmwRawCan()
                appendLog("[+] Ready (Deep OBD Raw CAN Mode): $stn")
                val snap = pollOnce()
                if (snap != null) {
                    _ui.update { it.copy(snap = snap) }
                    val vStr = if (snap.batteryV.isNaN()) "--" else "%.2fV".format(Locale.US, snap.batteryV)
                    val ptcStr = if (snap.ptcDutyPct.isNaN()) "--" else "%.0f%%".format(Locale.US, snap.ptcDutyPct)
                    appendLog(
                        "[+] IHKA_G11 Live -> 12V=$vStr, Mode=${snap.ventStateSummary()}, " +
                        "PTC=$ptcStr, Evap=${"%.1f".format(Locale.US, snap.evapC)}°C"
                    )
                } else {
                    appendLog("[!] No reply on 0xD8D7. Tap Start 3x for Diagnostic Mode!")
                }
            } catch (e: Exception) {
                appendLog("[!] Connect error: ${e.message}")
            } finally {
                _ui.update { it.copy(isBusy = false) }
            }
        }
    }

    private suspend fun pollOnce(): IhkaSnapshot? {
        val battV = ble.readBatteryVoltage()

        // 1. DID 0xD8D7: duct/cabin temperatures (rear ducts only populated on 4-zone cars)
        val rD7 = ble.bmwUdsRequest("22D8D7") ?: return null
        if (rD7.size < 14 || (rD7[0].toInt() and 0xFF) != 0x62) return null
        val d7 = rD7.copyOfRange(3, rD7.size)

        val evapC = (d7[0].toInt() and 0xFF) / 5.0f - 10.0f
        val ventFL = d7[1].toInt()
        val ventFR = d7[2].toInt()
        val footFL = d7[3].toInt()
        val footFR = d7[4].toInt()
        // Bytes 6..9 are the rear duct sensors; on a 2-zone car they read 0x80 or 0x00.
        updateZoneDetection((6..9).map { d7[it].toInt() and 0xFF })
        val hasRear = _ui.value.zones == 4
        val ventRL = if (hasRear) parseSensorTemp(d7[6]) else -999
        val ventRR = if (hasRear) parseSensorTemp(d7[7]) else -999
        val footRL = if (hasRear) parseSensorTemp(d7[8]) else -999
        val footRR = if (hasRear) parseSensorTemp(d7[9]) else -999
        val cabinC = d7[10].toInt()

        // 2. DID 0xD8D9: Outside Temp, Windshield RLBS Temp & RH, AC Pressure, Engine Coolant
        var outsideC = Float.NaN
        var wsC = Float.NaN
        var wsRh = Float.NaN
        var acBar: Int? = null
        var coolantC: Int? = null

        val rD9 = ble.bmwUdsRequest("22D8D9")
        if (rD9 != null && rD9.size >= 13 && (rD9[0].toInt() and 0xFF) == 0x62) {
            val d9 = rD9.copyOfRange(3, rD9.size)
            outsideC = (d9[1].toInt() and 0xFF) / 2.0f - 40.0f
            wsRh     = (d9[2].toInt() and 0xFF) / 2.0f
            wsC      = (d9[3].toInt() and 0xFF) / 2.0f - 40.0f
            acBar    = d9[7].toInt() and 0xFF
            coolantC = ((d9[8].toInt() shl 8) or (d9[9].toInt() and 0xFF)).toShort().toInt()
        }

        // 3. DID 0xD8DB: Active Aircon Settings (AUTO vs Manual Vent/Blower, AC, Recirc, Blower Steps)
        var autoBlowerL = -1
        var autoBlowerR = -1
        var aucOn = -1
        var recircOn = -1
        var defrostProgOn = -1
        var autoFlapL = -1
        var autoFlapR = -1
        var acOn = -1
        var maxAcOn = -1
        var systemOff = -1
        var blowerStepL = -1
        var blowerStepR = -1
        var blowerStepRear = -1

        val rDB = ble.bmwUdsRequest("22D8DB")
        if (rDB != null && rDB.size >= 22 && (rDB[0].toInt() and 0xFF) == 0x62) {
            val db = rDB.copyOfRange(3, rDB.size)
            autoBlowerL   = db[0].toInt() and 0xFF
            autoBlowerR   = db[1].toInt() and 0xFF
            aucOn         = db[3].toInt() and 0xFF
            recircOn      = db[4].toInt() and 0xFF
            defrostProgOn = db[7].toInt() and 0xFF
            autoFlapL     = db[8].toInt() and 0xFF
            autoFlapR     = db[9].toInt() and 0xFF
            acOn          = db[11].toInt() and 0xFF
            maxAcOn       = db[12].toInt() and 0xFF
            systemOff     = db[15].toInt() and 0xFF
            blowerStepL   = db[16].toInt() and 0xFF
            blowerStepR   = db[17].toInt() and 0xFF
            if (db.size >= 28) {
                blowerStepRear = db[27].toInt() and 0xFF
            }
        }

        // 4. DID 0xD8D8: Stratification Sliders (0=Cold, 50=Neutral, 100=Warm)
        var stratL = -1
        var stratR = -1
        var stratRL = -1
        var stratRR = -1

        val rD8 = ble.bmwUdsRequest("22D8D8")
        if (rD8 != null && rD8.size >= 9 && (rD8[0].toInt() and 0xFF) == 0x62) {
            val d8 = rD8.copyOfRange(3, rD8.size)
            stratL  = (d8[1].toInt() and 0xFF).let { if (it <= 100) it else -1 }
            stratR  = (d8[2].toInt() and 0xFF).let { if (it <= 100) it else -1 }
            stratRL = (d8[4].toInt() and 0xFF).let { if (it <= 100) it else -1 }
            stratRR = (d8[5].toInt() and 0xFF).let { if (it <= 100) it else -1 }
        }

        // 5. Routine 31 03 A1 2A: Target Setpoint Temperatures (°C) & SYNC check
        var targetFL = -1
        var targetFR = -1
        var targetRL = -1
        var targetRR = -1
        var syncActive = -1

        val rSet = ble.bmwUdsRequest("3103A12A")
        if (rSet != null && rSet.size >= 9 && (rSet[0].toInt() and 0xFF) == 0x71) {
            val sp = rSet.copyOfRange(4, rSet.size)
            targetFL = (sp[1].toInt() and 0xFF).let { if (it in 14..32) it else -1 }
            targetFR = (sp[2].toInt() and 0xFF).let { if (it in 14..32) it else -1 }
            targetRL = (sp[3].toInt() and 0xFF).let { if (it in 14..32) it else -1 }
            targetRR = (sp[4].toInt() and 0xFF).let { if (it in 14..32) it else -1 }
            if (targetFL > 0 && targetFR > 0) {
                val allMatch = (targetFL == targetFR) &&
                    (targetRL < 0 || targetRL == targetFL) &&
                    (targetRR < 0 || targetRR == targetFL)
                syncActive = if (allMatch) 1 else 0
            }
        }

        // 6. Routine 31 03 A1 28: Actual Blower Output Power (0..100%)
        var blowerPctFront = -1
        var blowerPctRear = -1

        val rBlw = ble.bmwUdsRequest("3103A128")
        if (rBlw != null && rBlw.size >= 6 && (rBlw[0].toInt() and 0xFF) == 0x71) {
            val bl = rBlw.copyOfRange(4, rBlw.size)
            blowerPctFront = (bl[0].toInt() and 0xFF).let { if (it <= 100) it else -1 }
            blowerPctRear  = (bl[1].toInt() and 0xFF).let { if (it <= 100) it else -1 }
        }

        // 7. Routine 31 03 A1 2B: Electric PTC Auxiliary Heater Duty Cycle (%) & PCB Temp (°C)
        var ptcDutyPct = Float.NaN
        var ptcTempC = -999
        var ptcCurrentA = Float.NaN

        val rPtc = ble.bmwUdsRequest("3103A12B")
        if (rPtc != null && rPtc.size >= 8 && (rPtc[0].toInt() and 0xFF) == 0x71) {
            val ptc = rPtc.copyOfRange(4, rPtc.size)
            val rawDuty = ptc[0].toInt() and 0xFF
            if (rawDuty <= 200) ptcDutyPct = rawDuty / 2.0f
            val rawCurr = ptc[2].toInt() and 0xFF
            if (rawCurr < 255) ptcCurrentA = rawCurr / 5.0f
            val rawTemp = ptc[3].toInt() and 0xFF
            if (rawTemp < 250) ptcTempC = rawTemp - 40
        }

        // 8. Routine 31 03 A1 22: Klappenmotoren Positionen (0..100%) — Front & Rear
        var flapDef = -1
        var flapVentL = -1
        var flapVentR = -1
        var flapFootL = -1
        var flapFootR = -1
        var flapRearDistL = -1
        var flapRearDistR = -1
        var flapMixL = -1
        var flapMixR = -1
        var flapRearMixL = -1
        var flapRearMixR = -1

        val rFlap = ble.bmwUdsRequest("3103A122")
        if (rFlap != null && rFlap.size >= 25 && (rFlap[0].toInt() and 0xFF) == 0x71) {
            val fl = rFlap.copyOfRange(4, rFlap.size)
            flapDef       = (fl[3].toInt() and 0xFF).let { if (it <= 100) it else -1 }
            flapVentL     = (fl[4].toInt() and 0xFF).let { if (it <= 100) it else -1 }
            flapVentR     = (fl[5].toInt() and 0xFF).let { if (it <= 100) it else -1 }
            flapFootL     = (fl[11].toInt() and 0xFF).let { if (it <= 100) it else -1 }
            flapFootR     = (fl[12].toInt() and 0xFF).let { if (it <= 100) it else -1 }
            flapRearDistL = (fl[13].toInt() and 0xFF).let { if (it <= 100) it else -1 }
            flapRearDistR = (fl[14].toInt() and 0xFF).let { if (it <= 100) it else -1 }
            flapMixL      = (fl[16].toInt() and 0xFF).let { if (it <= 100) it else -1 }
            flapMixR      = (fl[17].toInt() and 0xFF).let { if (it <= 100) it else -1 }
            flapRearMixL  = (fl[19].toInt() and 0xFF).let { if (it <= 100) it else -1 }
            flapRearMixR  = (fl[20].toInt() and 0xFF).let { if (it <= 100) it else -1 }
        }

        return IhkaSnapshot(
            batteryV = battV,
            evapC = evapC, cabinC = cabinC,
            ventFrontL = ventFL, ventFrontR = ventFR,
            footFrontL = footFL, footFrontR = footFR,
            ventRearL = ventRL, ventRearR = ventRR,
            footRearL = footRL, footRearR = footRR,
            outsideC = outsideC, windshieldC = wsC, windshieldRh = wsRh,
            acBar = acBar, coolantC = coolantC,
            acOn = acOn, maxAcOn = maxAcOn,
            autoFlapL = autoFlapL, autoFlapR = autoFlapR,
            autoBlowerL = autoBlowerL, autoBlowerR = autoBlowerR,
            aucOn = aucOn, recircOn = recircOn,
            defrostProgOn = defrostProgOn, systemOff = systemOff,
            blowerStepL = blowerStepL, blowerStepR = blowerStepR, blowerStepRear = blowerStepRear,
            stratL = stratL, stratR = stratR, stratRearL = stratRL, stratRearR = stratRR,
            targetFrontL = targetFL, targetFrontR = targetFR,
            targetRearL = targetRL, targetRearR = targetRR,
            syncActive = syncActive,
            blowerPctFront = blowerPctFront, blowerPctRear = blowerPctRear,
            ptcDutyPct = ptcDutyPct, ptcTempC = ptcTempC, ptcCurrentA = ptcCurrentA,
            flapDefrost = flapDef, flapVentL = flapVentL, flapVentR = flapVentR,
            flapFootL = flapFootL, flapFootR = flapFootR,
            flapRearDistL = flapRearDistL, flapRearDistR = flapRearDistR,
            flapMixL = flapMixL, flapMixR = flapMixR,
            flapRearMixL = flapRearMixL, flapRearMixR = flapRearMixR
        )
    }

    /** Signed °C (so sub-zero readings survive); 0x80 is the "no sensor / invalid" marker. */
    fun parseSensorTemp(rawByte: Byte): Int =
        if ((rawByte.toInt() and 0xFF) == 0x80) -999 else rawByte.toInt()

    fun startLogging() {
        if (!ble.isConnected || _ui.value.isLogging) {
            appendLog("[!] Connect to OBDLink CX first.")
            return
        }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val csvFile = File(app.getExternalFilesDir(null), "ihka_log_$stamp.csv")

        activeJob = viewModelScope.launch(Dispatchers.IO) {
            _ui.update {
                it.copy(
                    isLogging = true, sampleCount = 0,
                    sparkEvap = emptyList(), sparkFootL = emptyList(), sparkWindshield = emptyList()
                )
            }
            appendLog("[*] Logging 1 Hz ${_ui.value.zones}-Zone telemetry + PTC + Flaps -> ${csvFile.name}")

            val headers = listOf(
                "timestamp", "elapsed_s", "event_marker", "battery_v",
                "evap_c", "cabin_c",
                "vent_fl_c", "vent_fr_c", "foot_fl_c", "foot_fr_c",
                "vent_rl_c", "vent_rr_c", "foot_rl_c", "foot_rr_c",
                "outside_c", "windshield_c", "windshield_rh", "ac_bar", "b58_coolant_c",
                // Aircon Settings & State (0xD8DB, 0xD8D8, 0xA12A, 0xA128, 0xA12B)
                "target_fl_c", "target_fr_c", "target_rl_c", "target_rr_c", "sync_active",
                "ac_on", "max_ac_on", "auto_flap_l", "auto_flap_r", "auto_blower_l", "auto_blower_r",
                "recirc_on", "auc_on", "defrost_prog_on", "system_off",
                "blower_step_l", "blower_step_r", "blower_step_rear",
                "blower_pct_front", "blower_pct_rear",
                "ptc_duty_pct", "ptc_temp_c", "ptc_current_a",
                "strat_l_pct", "strat_r_pct", "strat_rl_pct", "strat_rr_pct",
                // Flap Positions (0xA122) — Front & Rear
                "flap_defrost_pct", "flap_vent_l_pct", "flap_vent_r_pct",
                "flap_foot_l_pct", "flap_foot_r_pct",
                "flap_rear_dist_l_pct", "flap_rear_dist_r_pct",
                "flap_mix_l_pct", "flap_mix_r_pct",
                "flap_rear_mix_l_pct", "flap_rear_mix_r_pct"
            )

            val t0 = System.nanoTime()
            try {
                csvFile.bufferedWriter().use { writer ->
                    writer.write(headers.joinToString(",") + "\n")
                    writer.flush()

                    var count = 0
                    while (isActive) {
                        val tLoop = System.nanoTime()
                        val elapsed = (tLoop - t0) / 1_000_000_000.0
                        val marker = pendingMarker.get()

                        val s = pollOnce()
                        if (s != null) {
                            val row = listOf(
                                LocalDateTime.now().toString(),
                                "%.2f".format(Locale.US, elapsed),
                                marker,
                                if (s.batteryV.isNaN()) "" else "%.2f".format(Locale.US, s.batteryV),
                                "%.2f".format(Locale.US, s.evapC),
                                s.cabinC.toString(),
                                s.ventFrontL.toString(), s.ventFrontR.toString(),
                                s.footFrontL.toString(), s.footFrontR.toString(),
                                fmtTemp(s.ventRearL), fmtTemp(s.ventRearR),
                                fmtTemp(s.footRearL), fmtTemp(s.footRearR),
                                if (s.outsideC.isNaN()) "" else "%.1f".format(Locale.US, s.outsideC),
                                if (s.windshieldC.isNaN()) "" else "%.1f".format(Locale.US, s.windshieldC),
                                if (s.windshieldRh.isNaN()) "" else "%.1f".format(Locale.US, s.windshieldRh),
                                s.acBar?.toString() ?: "",
                                s.coolantC?.toString() ?: "",
                                // Aircon Settings & State
                                fmtInt(s.targetFrontL), fmtInt(s.targetFrontR),
                                fmtInt(s.targetRearL), fmtInt(s.targetRearR),
                                fmtInt(s.syncActive),
                                fmtInt(s.acOn), fmtInt(s.maxAcOn),
                                fmtInt(s.autoFlapL), fmtInt(s.autoFlapR),
                                fmtInt(s.autoBlowerL), fmtInt(s.autoBlowerR),
                                fmtInt(s.recircOn), fmtInt(s.aucOn),
                                fmtInt(s.defrostProgOn), fmtInt(s.systemOff),
                                fmtInt(s.blowerStepL), fmtInt(s.blowerStepR), fmtInt(s.blowerStepRear),
                                fmtInt(s.blowerPctFront), fmtInt(s.blowerPctRear),
                                if (s.ptcDutyPct.isNaN()) "" else "%.1f".format(Locale.US, s.ptcDutyPct),
                                if (s.ptcTempC > -100) s.ptcTempC.toString() else "",
                                if (s.ptcCurrentA.isNaN()) "" else "%.1f".format(Locale.US, s.ptcCurrentA),
                                fmtInt(s.stratL), fmtInt(s.stratR), fmtInt(s.stratRearL), fmtInt(s.stratRearR),
                                // Flaps Front & Rear
                                fmtInt(s.flapDefrost), fmtInt(s.flapVentL), fmtInt(s.flapVentR),
                                fmtInt(s.flapFootL), fmtInt(s.flapFootR),
                                fmtInt(s.flapRearDistL), fmtInt(s.flapRearDistR),
                                fmtInt(s.flapMixL), fmtInt(s.flapMixR),
                                fmtInt(s.flapRearMixL), fmtInt(s.flapRearMixR)
                            )
                            writer.write(row.joinToString(",") + "\n")
                            writer.flush()
                            count++

                            // Row is on disk -> consume the marker (unless the user queued a new one meanwhile)
                            pendingMarker.compareAndSet(marker, "")

                            _ui.update { cur ->
                                cur.copy(
                                    sampleCount = count,
                                    snap = s,
                                    sparkEvap = (cur.sparkEvap + s.evapC).takeLast(120),
                                    sparkFootL = (cur.sparkFootL + s.footFrontL.toFloat()).takeLast(120),
                                    sparkWindshield = if (!s.windshieldC.isNaN()) (cur.sparkWindshield + s.windshieldC).takeLast(120) else cur.sparkWindshield
                                )
                            }
                        }
                        val rem = 1000L - (System.nanoTime() - tLoop) / 1_000_000L
                        if (rem > 20L) delay(rem)
                    }
                }
            } catch (_: CancellationException) {
                appendLog("[+] Saved ${csvFile.name} (${_ui.value.sampleCount} rows)")
            } catch (e: Exception) {
                appendLog("[!] Log error: ${e.message}")
            } finally {
                _ui.update { it.copy(isLogging = false) }
            }
        }
    }

    private fun fmtInt(v: Int): String = if (v >= 0) v.toString() else ""
    /** Signed temperature; -999 (no sensor / 2-zone car) becomes an empty cell like other missing values. */
    private fun fmtTemp(v: Int): String = if (v == -999) "" else v.toString()

    fun runFlapCalibrationRoutine() {
        if (!ble.isConnected || _ui.value.isBusy || _ui.value.isLogging) return
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(isBusy = true) }
            try {
                appendLog("[*] Starting IHKA_G11 Flap Calibration (31 01 A1 24)...")
                ble.bmwUdsRequest("1003")
                val start = ble.bmwUdsRequest("3101A124")
                if (start == null || (start[0].toInt() and 0xFF) != 0x71) {
                    appendLog("[!] Calibration start rejected (ensure engine OFF, Diagnostic Mode ON).")
                    return@launch
                }
                appendLog("[+] Flap sweep running! Polling 31 03 A1 24...")
                for (i in 1..20) {
                    delay(2000L)
                    ble.bmwUdsRequest("3E00")
                    val res = ble.bmwUdsRequest("3103A124")
                    if (res != null && res.size >= 6) {
                        val state = res[4].toInt() and 0xFF
                        val result = res[5].toInt() and 0xFF
                        appendLog("  [t=${i * 2}s] Status=$state (1=Running, 2=Done) | Result=$result (1=OK)")
                        if (state == 2) {
                            appendLog("[+] Flap Calibration Completed! (Result=${if (result == 1) "OK & Saved" else "NIO"})")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                appendLog("[!] Calibration error: ${e.message}")
            } finally {
                _ui.update { it.copy(isBusy = false) }
            }
        }
    }

    fun stopActiveTask() { activeJob?.cancel() }
    fun disconnectBle() { activeJob?.cancel(); ble.disconnect() }

    /** All CSV logs, newest first, flagged with whether they were shared before. */
    fun listCsvFiles(): List<CsvEntry> {
        val sent = sharePrefs.getStringSet(KEY_SENT, emptySet()) ?: emptySet()
        return (app.getExternalFilesDir(null)?.listFiles { f -> f.isFile && f.name.endsWith(".csv") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { CsvEntry(it, it.name in sent) }
    }

    fun markCsvSent(files: List<File>) {
        val sent = sharePrefs.getStringSet(KEY_SENT, emptySet()).orEmpty().toMutableSet()
        sent += files.map { it.name }
        // Drop names of files that no longer exist so the set doesn't grow forever.
        val existing = app.getExternalFilesDir(null)?.list()?.toSet() ?: emptySet()
        sent.retainAll(existing)
        sharePrefs.edit().putStringSet(KEY_SENT, sent).apply()
    }

    private companion object {
        const val KEY_SENT = "sent_files"
        const val KEY_ZONE_MODE = "zone_mode"
    }
}

data class CsvEntry(val file: File, val sent: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    val s = state.snap
    val context = LocalContext.current
    var showCalibConfirm by remember { mutableStateOf(false) }
    var shareEntries by remember { mutableStateOf<List<CsvEntry>?>(null) }

    shareEntries?.let { entries ->
        CsvShareDialog(
            entries = entries,
            onDismiss = { shareEntries = null },
            onShare = { files ->
                shareEntries = null
                shareCsvFiles(context, files)
                vm.markCsvSent(files)
            }
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> if (grants.values.all { it }) vm.connectAdapter() }

    if (showCalibConfirm) {
        AlertDialog(
            onDismissRequest = { showCalibConfirm = false },
            title = { Text("Calibrate IHKA Flaps?") },
            text = {
                Text(
                    "This runs the factory Flap Motor End-Stop Calibration routine (UDS 31 01 A1 24) and sweeps all LIN stepper motors between 0% and 100%.\n\n" +
                    "Ensure the engine is OFF and Ignition/Diagnostic Mode is ON (press Start 3x without brake) before proceeding."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCalibConfirm = false
                        vm.runFlapCalibrationRoutine()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Start Calibration", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCalibConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("G30/G31 IHKA ${state.zones}-zone Logger", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("${state.connStatus} · Rows: ${state.sampleCount}", fontSize = 12.5.sp, color = Color(0xFF64B5F6))
            }
            ZoneModeSelector(state, onSelect = vm::setZoneMode)
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    if (state.connStatus.startsWith("Connected")) vm.disconnectBle()
                    else permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) { Text(if (state.connStatus.startsWith("Connected")) "Disconnect" else "Connect CX", fontSize = 13.sp) }
        }

        // Row 1: Primary Global Sensors (12V Battery, Evaporator, Cabin, Windshield RLBS, Coolant)
        TileRow {
            MetricTile("12V Bus", if (s.batteryV.isNaN()) "--" else "${"%.2f".format(Locale.US, s.batteryV)}V", Color(0xFF66BB6A), Modifier.weight(0.95f))
            MetricTile("Evap", if (s.evapC.isNaN()) "--" else "${"%.1f".format(Locale.US, s.evapC)}°C", Color(0xFF29B6F6), Modifier.weight(0.95f))
            MetricTile("Cabin", "${s.cabinC}°C", Color(0xFFFFCA28), Modifier.weight(0.85f))
            MetricTile("Glass/RH", if (s.windshieldC.isNaN()) "--" else "${"%.0f".format(Locale.US, s.windshieldC)}°/${"%.0f".format(Locale.US, s.windshieldRh)}%", Color(0xFFBA68C8), Modifier.weight(1.15f))
            MetricTile("Coolant", s.coolantC?.let { "$it°C" } ?: "--", Color(0xFFEF5350), Modifier.weight(0.9f))
        }

        // Row 2: Duct Temperatures (Front Vents + Footwells; Rear only on 4-zone cars)
        fun duct(l: Int, r: Int) = "${if (l == -999) "--" else l}/${if (r == -999) "--" else r}°C"
        TileRow {
            MetricTile("Fr Vent L/R", duct(s.ventFrontL, s.ventFrontR), Color(0xFF4DD0E1), Modifier.weight(1f))
            MetricTile("Fr Foot L/R", duct(s.footFrontL, s.footFrontR), Color(0xFFFF8A65), Modifier.weight(1f))
            if (state.zones == 4) {
                MetricTile("Rr Vent L/R", duct(s.ventRearL, s.ventRearR), Color(0xFF81C784), Modifier.weight(1f))
                MetricTile("Rr Foot L/R", duct(s.footRearL, s.footRearR), Color(0xFFFF7043), Modifier.weight(1f))
            }
        }

        // Row 3: Aircon Panel Settings (Target Temps, SYNC, Vent State AUTO/MAN + AC, Blower Step & %, Stratification)
        val setFr = if (s.targetFrontL > 0) "${s.targetFrontL}/${s.targetFrontR}°" else "--"
        val setRr = if (s.targetRearL > 0) "${s.targetRearL}/${s.targetRearR}°" else "--"
        val syncTag = when (s.syncActive) { 1 -> "SYNC"; 0 -> "4Z"; else -> "" }
        val blwStr = if (s.blowerStepL >= 0) {
            val pct = if (s.blowerPctFront >= 0) " (${s.blowerPctFront}%)" else ""
            "S${s.blowerStepL}/${s.blowerStepR}$pct"
        } else "--"
        val stratStr = if (s.stratL >= 0) "${s.stratL}/${s.stratR}%" else "--"

        TileRow {
            MetricTile("Set F/R ($syncTag)", "$setFr·$setRr", Color(0xFFFFEE58), Modifier.weight(1.25f))
            MetricTile("Vent/AC Mode", s.ventStateSummary(), Color(0xFF26A69A), Modifier.weight(1.15f))
            MetricTile("Fan Stp (%)", blwStr, Color(0xFF42A5F5), Modifier.weight(1.05f))
            MetricTile("Strat L/R", stratStr, Color(0xFFAB47BC), Modifier.weight(0.85f))
        }

        // Row 4: Live Flap Positions (31 03 A1 22 Front & Rear), PTC Auxiliary Heater (31 03 A1 2B) & A/C Pressure
        val ptcStr = if (!s.ptcDutyPct.isNaN()) {
            val tPart = if (s.ptcTempC > -100) " ${s.ptcTempC}°C" else ""
            "${"%.0f".format(Locale.US, s.ptcDutyPct)}%$tPart"
        } else "--"

        TileRow {
            MetricTile("Def / Foot L·R", if (s.flapDefrost >= 0) "${s.flapDefrost}·${s.flapFootL}/${s.flapFootR}%" else "--", Color(0xFF90CAF9), Modifier.weight(1.15f))
            MetricTile("Mix Fr L/R", if (s.flapMixL >= 0) "${s.flapMixL}/${s.flapMixR}%" else "--", Color(0xFFFFCC80), Modifier.weight(1.0f))
            MetricTile("Mix Rr L/R", if (s.flapRearMixL >= 0) "${s.flapRearMixL}/${s.flapRearMixR}%" else "--", Color(0xFFA5D6A7), Modifier.weight(1.0f))
            MetricTile("PTC Heater", ptcStr, Color(0xFFFF5252), Modifier.weight(1.05f))
            MetricTile("A/C", s.acBar?.let { "${it}b" } ?: "--", Color(0xFFB0BEC5), Modifier.weight(0.65f))
        }

        // Action Buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = { if (state.isLogging) vm.stopActiveTask() else vm.startLogging() },
                enabled = !state.isBusy,
                colors = ButtonDefaults.buttonColors(containerColor = if (state.isLogging) Color(0xFFD32F2F) else Color(0xFF2E7D32)),
                modifier = Modifier.weight(1.4f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) { Text(if (state.isLogging) "STOP LOG" else "START 1Hz LOG", fontWeight = FontWeight.Bold, fontSize = 13.sp) }

            OutlinedButton(
                onClick = { showCalibConfirm = true },
                enabled = !state.isLogging && !state.isBusy,
                modifier = Modifier.weight(1.1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) { Text("CALIB FLAPS", fontSize = 12.sp) }

            OutlinedButton(
                onClick = { shareEntries = vm.listCsvFiles() },
                modifier = Modifier.weight(0.9f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) { Text("SHARE CSV", fontSize = 12.sp) }
        }

        if (state.isLogging) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.flagCabinEvent("HOT_SWING") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                    modifier = Modifier.weight(1f).height(42.dp)
                ) { Text("MARK HOT BLAST", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                Button(
                    onClick = { vm.flagCabinEvent("COLD_SWING") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0277BD)),
                    modifier = Modifier.weight(1f).height(42.dp)
                ) { Text("MARK COLD BLAST", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
        }

        SparklineBox(state.sparkEvap, state.sparkFootL, state.sparkWindshield, Modifier.fillMaxWidth().height(76.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0A0D10), RoundedCornerShape(8.dp)).padding(8.dp)
        ) {
            items(state.consoleLines) { ln ->
                Text(ln, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFB0BEC5))
            }
        }
    }
}

@Composable
fun ZoneModeSelector(state: UiState, onSelect: (ZoneMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (state.zoneMode) {
        ZoneMode.AUTO -> "Auto: " + (state.detectedZones?.let { "${it}Z" } ?: "?")
        ZoneMode.TWO_ZONE -> "2Z"
        ZoneMode.FOUR_ZONE -> "4Z"
    }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        ) { Text(label, fontSize = 12.sp) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ZoneMode.entries.forEach { mode ->
                val text = if (mode == ZoneMode.AUTO) {
                    "Auto-detect" + (state.detectedZones?.let { " (found $it-zone)" } ?: "")
                } else mode.label
                DropdownMenuItem(
                    text = { Text(text, fontWeight = if (mode == state.zoneMode) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { expanded = false; onSelect(mode) }
                )
            }
        }
    }
}

@Composable
fun CsvShareDialog(entries: List<CsvEntry>, onDismiss: () -> Unit, onShare: (List<File>) -> Unit) {
    // Unsent files start checked, already-sent ones unchecked.
    val checked = remember(entries) {
        mutableStateMapOf<String, Boolean>().apply { entries.forEach { put(it.file.name, !it.sent) } }
    }
    val selected = entries.filter { checked[it.file.name] == true }.map { it.file }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share CSV logs") },
        text = {
            if (entries.isEmpty()) {
                Text("No CSV logs found.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(entries, key = { it.file.name }) { e ->
                        val isChecked = checked[e.file.name] == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { checked[e.file.name] = !isChecked }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = isChecked, onCheckedChange = { checked[e.file.name] = it })
                            Column {
                                Text(
                                    csvDisplayName(e.file),
                                    fontSize = 14.sp,
                                    fontWeight = if (e.sent) FontWeight.Normal else FontWeight.Bold
                                )
                                Text(
                                    "${"%.1f".format(Locale.US, e.file.length() / 1024f)} KB" +
                                        if (e.sent) " · sent" else "",
                                    fontSize = 11.sp,
                                    color = Color(0xFF90A4AE)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onShare(selected) }, enabled = selected.isNotEmpty()) {
                Text("Share (${selected.size})")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private val CSV_STAMP_IN = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
private val CSV_STAMP_OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm:ss")

/** "ihka_log_20261115_143005.csv" (or legacy "g31_ihka_4zone_…") -> "2026-11-15  14:30:05"; falls back to the raw file name. */
fun csvDisplayName(file: File): String {
    val stamp = Regex("""(\d{8}_\d{6})\.csv$""").find(file.name)?.groupValues?.get(1) ?: return file.name
    return runCatching { LocalDateTime.parse(stamp, CSV_STAMP_IN).format(CSV_STAMP_OUT) }.getOrDefault(file.name)
}

fun shareCsvFiles(context: Context, files: List<File>) {
    if (files.isEmpty()) return
    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
    val share = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
    }.apply {
        type = "text/csv"
        // Explicit ClipData so the read grant covers every URI, including through the chooser.
        clipData = ClipData.newUri(context.contentResolver, files[0].name, uris[0]).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val title = if (files.size == 1) "Share ${files[0].name}" else "Share ${files.size} CSV logs"
    context.startActivity(Intent.createChooser(share, title))
}

/**
 * Shared auto-sizing for the tiles of one row: every tile reports the largest font (in px) at which
 * its label and its value still fit on one line; all labels in the row use the smallest label fit and
 * all values the smallest value fit. Sizes only shrink, so they don't jump as values change length;
 * they reset when a tile's width changes (rotation, rear tiles shown/hidden).
 */
@Stable
class TileTextSizes {
    private val widthsPx = mutableMapOf<Any, Int>()
    private val labelFitsPx = mutableMapOf<Any, Float>()
    private val valueFitsPx = mutableMapOf<Any, Float>()
    private var labelPx by mutableStateOf<Float?>(null)
    private var valuePx by mutableStateOf<Float?>(null)

    fun labelPx(minPx: Float, maxPx: Float, defaultPx: Float): Float = (labelPx ?: defaultPx).coerceIn(minPx, maxPx)
    fun valuePx(minPx: Float, maxPx: Float, defaultPx: Float): Float = (valuePx ?: defaultPx).coerceIn(minPx, maxPx)

    fun report(id: Any, widthPx: Int, labelFit: Float, valueFit: Float) {
        val oldWidth = widthsPx.put(id, widthPx)
        labelFitsPx[id] = labelFit
        valueFitsPx[id] = valueFit
        val minLabel = labelFitsPx.values.min()
        val minValue = valueFitsPx.values.min()
        if (oldWidth != null && oldWidth != widthPx) {
            // Layout changed: start over from what fits now.
            labelPx = minLabel
            valuePx = minValue
        } else {
            // Shrink only (state is written only when it actually gets smaller).
            if (minLabel < (labelPx ?: Float.MAX_VALUE)) labelPx = minLabel
            if (minValue < (valuePx ?: Float.MAX_VALUE)) valuePx = minValue
        }
    }

    fun remove(id: Any) {
        widthsPx.remove(id)
        labelFitsPx.remove(id)
        valueFitsPx.remove(id)
    }
}

val LocalTileTextSizes = staticCompositionLocalOf { TileTextSizes() }

/** A row of [MetricTile]s sharing one label size and one value size. */
@Composable
fun TileRow(content: @Composable RowScope.() -> Unit) {
    val sizes = remember { TileTextSizes() }
    CompositionLocalProvider(LocalTileTextSizes provides sizes) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), content = content)
    }
}

/** Largest font size in px at which [text] fits in [maxWidthPx] on one line. Width scales linearly with px size. */
private fun fitFontPx(measurer: TextMeasurer, text: String, weight: FontWeight, maxWidthPx: Int, density: Density): Float {
    if (text.isEmpty() || maxWidthPx <= 0) return Float.MAX_VALUE
    val refPx = 100f
    val refSp = with(density) { refPx.toSp() }
    val width = measurer.measure(
        text, TextStyle(fontSize = refSp, fontWeight = weight), maxLines = 1, softWrap = false
    ).size.width
    return if (width <= 0) Float.MAX_VALUE else refPx * maxWidthPx / width * 0.97f
}

@Composable
fun MetricTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    val sizes = LocalTileTextSizes.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Stable identity for this tile, independent of its (possibly changing) label text.
    val id = remember { Any() }
    DisposableEffect(id) { onDispose { sizes.remove(id) } }

    BoxWithConstraints(modifier = modifier.background(Color(0xFF1A2027), RoundedCornerShape(8.dp)).padding(vertical = 6.dp, horizontal = 6.dp)) {
        val widthPx = constraints.maxWidth
        val labelFit = remember(label, widthPx, density) { fitFontPx(measurer, label, FontWeight.Normal, widthPx, density) }
        val valueFit = remember(value, widthPx, density) { fitFontPx(measurer, value, FontWeight.Bold, widthPx, density) }
        SideEffect { sizes.report(id, widthPx, labelFit, valueFit) }

        val labelSize = with(density) { sizes.labelPx(7.sp.toPx(), 18.sp.toPx(), 10.5.sp.toPx()).toSp() }
        val valueSize = with(density) { sizes.valuePx(9.sp.toPx(), 34.sp.toPx(), 14.5.sp.toPx()).toSp() }
        Column {
            Text(label, fontSize = labelSize, color = Color(0xFF90A4AE), maxLines = 1, softWrap = false)
            Text(value, fontSize = valueSize, fontWeight = FontWeight.Bold, color = accent, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
fun SparklineBox(evap: List<Float>, footL: List<Float>, wsC: List<Float>, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color(0xFF161B21), RoundedCornerShape(8.dp)).padding(6.dp)) {
        if (evap.size < 2) {
            Text("120s Live Graph (Cyan=Evap, Orange=Footwell L, Purple=Windshield Glass)", fontSize = 11.sp, color = Color(0xFF607D8B), modifier = Modifier.align(Alignment.Center))
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val all = evap + footL + wsC
                val minV = (all.minOrNull() ?: -5f) - 2f
                val maxV = (all.maxOrNull() ?: 55f) + 2f
                val span = (maxV - minV).coerceAtLeast(1f)
                fun drawLine(pts: List<Float>, color: Color) {
                    if (pts.size < 2) return
                    val path = Path()
                    pts.forEachIndexed { i, v ->
                        val x = size.width * (i.toFloat() / (pts.size - 1).coerceAtLeast(1))
                        val y = size.height * (1f - (v - minV) / span)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = color, style = Stroke(width = 3f))
                }
                drawLine(evap, Color(0xFF29B6F6))
                drawLine(footL, Color(0xFFFF8A65))
                drawLine(wsC, Color(0xFFBA68C8))
            }
        }
    }
}
