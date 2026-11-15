# BMW G31 540i · 4-Zone Climate (`IHKA_G11`) Telemetry Logger & Analyzer (v7)

Custom Android (Kotlin / Jetpack Compose) + Python diagnostic suite for the **BMW G31 540i Touring** equipped with **4-Zone Automatic Climate Control (`S4NBA`)** and an **OBDLink CX (Bluetooth LE / `STN2310`)** adapter.

All UDS Data Identifiers (`0x22`), Routine Control Identifiers (`0x31`), byte offsets, and scaling formulas in this project are extracted directly from BMW's factory **`ihka_g11.prg`** ECU description file (`ECU 0x78` on CAN ID `0x6F1` -> `0x678`).

---

## 1. Quick-Start Guide (In the Car)

1. **Plug in OBDLink CX** into the driver-side footwell OBD-II port.
2. **Wake the Vehicle:**
   * **Driving Log:** Start the B58 engine normally.
   * **Engine-Off Diagnostic Mode (for static inspection or Flap Calibration):** With your foot **OFF** the brake pedal, press the **Start/Stop button 3 times rapidly** until the instrument cluster displays *"Diagnostic mode active"*.
3. **Connect:** Open **G31 IHKA Logger** on your Android phone and tap **`Connect CX`**. Grant Bluetooth permissions if prompted. The status line will show `Connected (MTU ...)` and print the initial live snapshot in the bottom console.
4. **Record 1 Hz Telemetry:** Tap **`START 1Hz LOG`**.
   * Whenever you feel an uncommanded wave of hot air (footwells or windshield), tap **`MARK HOT BLAST`**.
   * Whenever you feel an uncommanded wave of cold air, tap **`MARK COLD BLAST`**.
   * These buttons inject `HOT_SWING` and `COLD_SWING` markers into the next 1 Hz CSV row so the Python analyzer draws vertical reference lines across all 5 graphs at the exact second you felt the change.
5. **Stop & Export:** Tap **`STOP LOG`**, then tap **`SHARE CSV`** to send `g31_ihka_4zone_YYYYMMDD_HHMMSS.csv` via Google Drive, Gmail, WhatsApp, or Nearby Share to your PC.

---

## 2. Complete Screen Reference ("User Manual" for Every Tile)

The Android dashboard is organized into **4 rows of live metric tiles**, followed by the action bar, 120-second live sparkline graph, and raw UDS log console.

### Row 1: Global Vehicle & Environmental Sensors

| Tile Label | Example Display | Source Register (`ihka_g11.prg`) | Exact Formula | What It Means & Normal Values |
|---|---|---|---|---|
| **`12V Bus`** | `14.48V` | OBDLink CX `ATRV` (Pin 16 ADC) | Direct `0.01V` ADC | **Vehicle 12V electrical bus voltage.**<br>• Engine running: **14.2V – 14.8V** (may dip to ~12.8V during coasting/smart-alternator decoupling).<br>• Engine off (Diagnostic Mode): **12.2V – 12.7V**.<br>• *Diagnostic clue:* If voltage sags below ~12.0V while running, BMW Power Management (`IBS`) may load-shed electric heaters/blowers. |
| **`Evap`** | `2.4°C` | `DID 0xD8D7` Byte `0` (`STAT_TEMP_VERDAMPFER_WERT`) | `raw / 5.0 - 10.0` (°C) | **A/C Evaporator Core fin temperature** (upstream of heater core).<br>• Normal with A/C ON: **+2.0°C to +4.5°C**.<br>• *Diagnostic clue:* If `Evap` drops below **+1.0°C / 0.0°C** (like the `-0.4°C` caught in your Oct 10 trace), IHKA triggers an emergency anti-icing compressor cut-out, causing sudden warm/humid air waves! |
| **`Cabin`** | `21°C` | `DID 0xD8D7` Byte `10` (`STAT_TEMP_INNENRAUM_UNGEDAEMPFT_WERT`) | `int8` (°C) | **Undamped interior cabin thermistor** behind the tiny intake grille on the front climate faceplate.<br>• Should track actual cockpit temperature within 1–2°C.<br>• *Diagnostic clue:* If `Cabin` stays frozen at a low value (e.g., `17°C`) or lags far behind true cabin warmth due to dust/aspirator fan stall, IHKA will continuously roast all 4 footwells! |
| **`Glass/RH`** | `11°/64%` | `DID 0xD8D9` Bytes `3` & `2` (`STAT_BUS_IN_RLBS_TEMP_FRONTSCHEIBE_WERT` & `LUFTFEUCHTIGKEIT`) | Glass: `raw / 2.0 - 40.0` (°C)<br>RH: `raw / 2.0` (%) | **Inner windshield glass temperature (°C) and local relative humidity (%)** measured by the mirror-base condensation sensor (`RLBS`).<br>• *Diagnostic clue:* When glass temp drops within ~1–2°C of the local dew point (high RH% in 0°C weather), IHKA silently overrides `AUTO` mode and blasts 30°C air up the windshield defrost duct (*Beschlagschutz*). |
| **`Coolant`** | `88°C` | `DID 0xD8D9` Bytes `8..9` (`STAT_BUS_IN_KUEHLMITTEL_MOTOR_TEMP_WERT`) | `int16_be` (°C) | **B58 engine coolant temperature** received by IHKA over the CAN bus.<br>• Warmed-up B58: **85°C – 108°C**.<br>• Below 30°C on cold start, `AUTO` suppresses main blower speed to avoid cold drafts. |

---

### Row 2: 4-Zone Outlet Duct Temperatures (`DID 0xD8D7`)

All 8 outlet duct thermistors are read in a single multi-frame burst from `DID 0xD8D7` (`RES_0xD8D7_D`) as signed 1°C integers (`int8`):

| Tile Label | Example Display | Source Bytes (`0xD8D7`) | What It Means & Expected Behavior in `AUTO` (~21°C Target) |
|---|---|---|---|
| **`Fr Vent L/R`** | `20/20°C` | Byte `1` (Left) / Byte `2` (Right) (`STAT_TEMP_BELUEFTUNG_LINKS/RECHTS_WERT`) | **Front dashboard face-level outlet temperatures (Driver / Passenger).**<br>• In steady-state `AUTO`, BMW's *"warm feet, cool head"* curve keeps face vents crisp around **19°C – 22°C**. |
| **`Fr Foot L/R`** | `32/32°C` | Byte `3` (Left) / Byte `4` (Right) (`STAT_TEMP_FUSSRAUM_LINKS/RECHTS_WERT`) | **Front driver and passenger footwell duct temperatures.**<br>• During cold warm-up surge: **38°C – 45°C**.<br>• During steady cruise: **28°C – 34°C**.<br>• *Diagnostic clue:* Sustained **45°C – 50°C+** after the cabin is already warm indicates either an under-reading `Cabin` sensor or an aggressive cold face-stratification offset. |
| **`Rr Vent L/R`** | `21/21°C` | Byte `6` (Rear Left) / Byte `7` (Rear Right) (`STAT_TEMP_BELUEFTUNG_HINTEN_LINKS/RECHTS_WERT`) | **Rear center-console face-level outlet temperatures (4-Zone).**<br>• Should hover near **20°C – 23°C** when rear stratification is neutral. |
| **`Rr Foot L/R`** | `33/33°C` | Byte `8` (Rear Left) / Byte `9` (Rear Right) (`STAT_TEMP_FUSSRAUM_HINTEN_LINKS/RECHTS_WERT`) | **Rear under-seat footwell duct temperatures (4-Zone).**<br>• *Diagnostic clue:* In your Oct 10 trace, these climbed to **56°C / 54°C**, directly matching rear passenger complaints of overheated feet. |

---

### Row 3: Aircon Panel Settings & Operating State

| Tile Label | Example Display | Source Register (`ihka_g11.prg`) | What It Means |
|---|---|---|---|
| **`Set F/R (SYNC/4Z)`** | `21/21°·21/21°` | `Routine 31 03 A1 2A` Bytes `1..4` (`RES_0xA12A_R`) | **Target temperature setpoints (`16°C – 28°C`)** for **Front Left / Front Right · Rear Left / Rear Right**.<br>• Header shows **`(SYNC)`** when all 4 zones are synchronized to the same temperature, or **`(4Z)`** when any zone is set independently. |
| **`Vent/AC Mode`** | `AUTO AC:ON` | `DID 0xD8DB` Bytes `7, 8, 9, 11, 12, 15` (`RES_0xD8DB_D`) | **Active air distribution & compressor mode:**<br>• `AUTO` vs `MAN`: Whether damper flap distribution is controlled automatically by IHKA (`1` = `AUTO`) or locked to manual vent selection (`0` = `MAN`). *(Note: Anti-fogging windshield overrides can only occur in `AUTO`!)*.<br>• `AC:ON` / `AC:OFF` / `MAX-AC`: Whether A/C cooling/dehumidification is enabled.<br>• `MAX DEF`: Windshield Max Defrost active.<br>• `OFF`: HVAC system switched off. |
| **`Fan Stp (%)`** | `S2/2 (38%)` | `DID 0xD8DB` Bytes `16, 17` + `Routine 31 03 A1 28` Byte `0` | **Selected fan bar step (`S1..S5` in AUTO intensity, `S1..S7` in Manual) for Driver/Passenger**, followed in parentheses by the **actual Front Blower Motor PWM output (`0..100%`)** from `0xA128`. |
| **`Strat L/R`** | `50/50%` | `DID 0xD8D8` Bytes `1, 2` (`RES_0xD8D8_D`) | **Upper-body stratification slider setting (Driver / Passenger).**<br>• `0%` = Full Cold (Blue), **`50%` = Neutral (Center tick)**, `100%` = Full Warm (Red). |

---

### Row 4: Live LIN Flap Positions, Electric PTC Heater & A/C Pressure

| Tile Label | Example Display | Source Register (`ihka_g11.prg`) | What It Means |
|---|---|---|---|
| **`Def / Foot L·R`** | `15·75/75%` | `Routine 31 03 A1 22` Byte `3` (Defrost) · Byte `11` (Footwell L) / Byte `12` (Footwell R) | **Actual stepper motor opening (`0%` = closed, `100%` = fully open) for the Windshield Defrost flap (`B3`), Front Left Footwell flap (`B11`), and Front Right Footwell flap (`B12`).**<br>• These are **two physically separate LIN stepper motors** (`0x801115` Left vs `0x801118` Right) and can differ whenever left/right `AUTO` intensity, sunlight, or manual vent distribution differs.<br>• *Diagnostic clue:* Watch `Def` (`Byte 3`) when the windshield vent swings between 15°C and 30°C—if `Def` jumps from ~10% to 80%+ on its own, `IHKA` is actively running an anti-fogging cycle! |
| **`Mix Fr L/R`** | `65/65%` | `Routine 31 03 A1 22` Byte `16` (Mix L) / Byte `17` (Mix R) (`STAT_ISTPOSITION_MISCHLUFT_LINKS/RECHTS_WERT`) | **Front Left & Right Hot/Cold Temperature Blend Flaps (`0%` = 100% Cold Evaporator bypass, `100%` = 100% Hot Heater Core/PTC).** |
| **`Mix Rr L/R`** | `70/70%` | `Routine 31 03 A1 22` Byte `19` (Rear Mix L) / Byte `20` (Rear Mix R) (`STAT_ISTPOSITION_MISCHLUFT_HINTEN_LINKS/RECHTS_WERT`) | **Rear Left & Right Hot/Cold Temperature Blend Flaps (`0%` = Cold, `100%` = Full Hot).**<br>• *Diagnostic clue:* If `Mix Rr L/R` sits at `85%–100%` while `Rr Foot L/R` is already 50°C+, IHKA is intentionally commanding maximum rear heat. |
| **`PTC Heater`** | `60% 58°C` | `Routine 31 03 A1 2B` Byte `0` (`raw / 2.0 %`) & Byte `3` (`raw - 40 °C`) (`RES_0xA12B_R`) | **12V Electric PTC Auxiliary Heater PWM Duty Cycle (`0..100%`) and internal PCB/Heater temperature (`°C`).**<br>• Fires immediately on cold starts and boosts air warmth alongside the hot-water heater core. |
| **`A/C`** | `11b` | `DID 0xD8D9` Byte `7` (`STAT_BUS_IN_KAELTEKREISLAUF_DRUCK_WERT`) | **A/C refrigerant high-side circuit pressure in bar.**<br>• Typical range: **4–6 bar** (A/C off in cold weather) to **8–16 bar** (compressor active). |

---

### Action Buttons Explained

* **`START 1Hz LOG` / `STOP LOG`:** Starts/stops writing all **57 telemetry columns** at 1 sample per second to `/Android/data/com.g31.ihkalogger/files/g31_ihka_4zone_YYYYMMDD_HHMMSS.csv`.
* **`CALIB FLAPS` (`UDS 31 01 A1 24`):**
  * **Why it is here:** Earlier in our troubleshooting, you asked how to run BMW's **"Flap Motor End-Stop Calibration"** (`KLAPPENMOTOR_KALIBRIERLAUF`) without paying $99 for ProTool or installing a 150 GB ISTA+ setup. This button executes that exact factory service routine (`31 01 A1 24` followed by `31 03 A1 24` status polling) for free!
  * **When to use it:** Only if you ever replace a stepper motor, unplug the climate housing, or suspect a flap has lost its 0%/100% mechanical end-stops. *(Run with ignition in **Diagnostic Mode, engine OFF**; disabled automatically while logging).*
* **`SHARE CSV`:** Opens the Android system share sheet for the most recently recorded `.csv` log file.

---

## 3. Desktop 5-Panel Python Analyzer (`python_tools/plot_ihka_log.py`)

Install dependencies once on your PC:
```bash
pip install numpy matplotlib
```

Run the 5-panel diagnostic plotter on any exported CSV log:
```bash
python python_tools/plot_ihka_log.py --csv g31_ihka_4zone_YYYYMMDD_HHMMSS.csv --save g31_analysis.png
```
