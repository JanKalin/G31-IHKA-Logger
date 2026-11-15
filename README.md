# G30/G31 IHKA Logger · BMW Climate (`IHKA_G11`) Telemetry Logger & Analyzer

Custom Android (Kotlin / Jetpack Compose) + Python diagnostic suite for **BMW G30 / G31 5 Series** cars with **2-Zone or 4-Zone Automatic Climate Control** (4-zone = option `S4NBA`) and an **OBDLink CX (Bluetooth LE / `STN2310`)** adapter. Developed and tested on a G31 540i Touring with 4-zone climate.

---

## 0. Installation

**Requirements:** Android 12 or newer (`minSdk 31`) with Bluetooth LE, and an OBDLink CX adapter.

1. Open the repository's **[Releases](https://github.com/JanKalin/G31-IHKA-Logger/releases)** page on your phone and download the latest `G30-G31-IHKA-Logger-X.Y.Z.apk`.
2. Open the downloaded file. Android will ask you to allow your browser / file manager to *install unknown apps* — allow it once, then confirm the install.
3. **Updates:** download and install the newer APK over the old one (your logs are kept). For automatic update notifications, add the repository URL to [Obtainium](https://github.com/ImranR98/Obtainium), which tracks GitHub Releases.

---

## 1. Quick-Start Guide (In the Car)

1. **Plug in OBDLink CX** into the driver-side footwell OBD-II port.
2. **Wake the Vehicle:**
   * **Driving Log:** Start the engine normally.
   * **Engine-Off Diagnostic Mode (for static inspection or Flap Calibration):** With your foot **OFF** the brake pedal, press the **Start/Stop button 3 times rapidly** until the instrument cluster displays *"Diagnostic mode active"*.
3. **Connect:** Open **IHKA Logger** on your Android phone and tap **`Connect CX`**. Grant Bluetooth permissions if prompted. The status line will show `Connected (MTU ...)` and print the initial live snapshot in the bottom console.
4. **Check the zone count:** The title shows **`2-zone`** or **`4-zone`**, auto-detected from the first few readings (see [Header & Zone Selector](#header--zone-selector)). If it is wrong for your car, pick the correct one from the zone button in the header — the choice is remembered.
5. **Record 1 Hz Telemetry:** Tap **`START 1Hz LOG`**.
   * Whenever you feel an uncommanded wave of hot air (footwells or windshield), tap **`MARK HOT BLAST`**.
   * Whenever you feel an uncommanded wave of cold air, tap **`MARK COLD BLAST`**.
   * These buttons inject `HOT_SWING` and `COLD_SWING` markers into the next 1 Hz CSV row so the Python analyzer draws vertical reference lines across all 5 graphs at the exact second you felt the change.
6. **Stop & Export:** Tap **`STOP LOG`**, then tap **`SHARE CSV`**, tick the logs you want (logs not yet shared are pre-selected), and send them via Google Drive, Gmail, WhatsApp, or Nearby Share to your PC.

---

## 2. Complete Screen Reference ("User Manual" for Every Tile)

The Android dashboard is organized into a header, **4 rows of live metric tiles**, the action bar, a 120-second live sparkline graph, and the raw UDS log console. Values that are unavailable are shown as **`--`**. The UDS request, byte offset and scaling behind every tile are listed in [REGISTERS.md](REGISTERS.md).

### Header & Zone Selector

* **Title — `G30/G31 IHKA 2-zone Logger` / `4-zone Logger`:** the zone count currently in effect.
* **Status line:** Bluetooth connection state and the number of CSV rows recorded in the current log.
* **Zone button (`Auto: 4Z`, `Auto: 2Z`, `Auto: ?`, `2Z`, `4Z`):** opens a menu with **Auto-detect**, **2-Zone** and **4-Zone**. The selection is saved and survives app restarts.
  * **Auto-detect** uses the rear duct sensor bytes `6..9` of `DID 0xD8D7`. The `ihka_g11.prg` variant is shared by 2-zone and 4-zone modules and always returns the full array, but on a 2-zone car those unpopulated channels read `0x80` or `0x00`. Any valid rear reading locks in **4-zone** for the session; **2-zone** is chosen only after 3 consecutive samples with all four rear bytes unpopulated (a real 4-zone duct at exactly 0°C also reads `0x00`). Until a decision is made the app assumes 4-zone (`Auto: ?`). The detection result is printed in the console.
  * **2-Zone / 4-Zone** override auto-detection, for the rare case where it guesses wrong.
* **`Connect CX` / `Disconnect`:** scans for and connects to the OBDLink CX, or disconnects.

### Row 1: Global Vehicle & Environmental Sensors

| Tile Label | Example Display | What It Means & Normal Values | Diagnostic Clue |
|---|---|---|---|
| **`12V Bus`** | `14.48V` | **Vehicle 12V electrical bus voltage.**<br>• Engine running: **14.2V – 14.8V** (may dip to ~12.8V during coasting/smart-alternator decoupling).<br>• Engine off (Diagnostic Mode): **12.2V – 12.7V**. | If voltage sags below ~12.0V while running, BMW Power Management (`IBS`) may load-shed electric heaters/blowers. |
| **`Evap`** | `2.4°C` | **A/C Evaporator Core fin temperature** (upstream of heater core).<br>• Normal with A/C ON: **+2.0°C to +4.5°C**. | If `Evap` drops below **+1.0°C / 0.0°C** (readings as low as `-0.4°C` have been logged), IHKA triggers an emergency anti-icing compressor cut-out, causing sudden warm/humid air waves! |
| **`Cabin`** | `21°C` | **Undamped interior cabin thermistor** behind the tiny intake grille on the front climate faceplate.<br>• Should track actual cockpit temperature within 1–2°C. | If `Cabin` stays frozen at a low value (e.g., `17°C`) or lags far behind true cabin warmth due to dust/aspirator fan stall, IHKA will continuously roast all 4 footwells! |
| **`Glass/RH`** | `11°/64%` | **Inner windshield glass temperature (°C) and local relative humidity (%)** measured by the mirror-base condensation sensor (`RLBS`). | When glass temp drops within ~1–2°C of the local dew point (high RH% in 0°C weather), IHKA silently overrides `AUTO` mode and blasts 30°C air up the windshield defrost duct (*Beschlagschutz*). |
| **`Coolant`** | `88°C` | **Engine coolant temperature** received by IHKA over the CAN bus (logged as `b58_coolant_c`, but valid for any engine).<br>• Warmed-up B58: **85°C – 108°C**.<br>• Below 30°C on cold start, `AUTO` suppresses main blower speed to avoid cold drafts. | — |

---

### Row 2: Outlet Duct Temperatures

All outlet duct temperatures (8 on a 4-zone car, 4 on a 2-zone car) are read in a single request as signed 1°C values, so sub-zero readings are preserved. On **2-zone** cars the two **`Rr`** tiles are hidden and the front tiles take the full row. A rear sensor reporting "no sensor / invalid" is shown as `--`.

| Tile Label | Example Display | What It Means & Expected Behavior in `AUTO` (~21°C Target) | Diagnostic Clue |
|---|---|---|---|
| **`Fr Vent L/R`** | `20/20°C` | **Front dashboard face-level outlet temperatures (Driver / Passenger).**<br>• In steady-state `AUTO`, BMW's *"warm feet, cool head"* curve keeps face vents crisp around **19°C – 22°C**. | — |
| **`Fr Foot L/R`** | `32/32°C` | **Front driver and passenger footwell duct temperatures.**<br>• During cold warm-up surge: **38°C – 45°C**.<br>• During steady cruise: **28°C – 34°C**. | Sustained **45°C – 50°C+** after the cabin is already warm indicates either an under-reading `Cabin` sensor or an aggressive cold face-stratification offset. |
| **`Rr Vent L/R`** | `21/21°C` | **Rear center-console face-level outlet temperatures (4-Zone).**<br>• Should hover near **20°C – 23°C** when rear stratification is neutral. | — |
| **`Rr Foot L/R`** | `33/33°C` | **Rear under-seat footwell duct temperatures (4-Zone).** | Sustained readings above **~50°C** (e.g. **56°C / 54°C** in one logged drive) match rear passenger complaints of overheated feet. |

---

### Row 3: Aircon Panel Settings & Operating State

| Tile Label | Example Display | What It Means | Diagnostic Clue |
|---|---|---|---|
| **`Set F/R (SYNC/4Z)`** | `21/21°·21/21°` | **Target temperature setpoints (`16°C – 28°C`)** for **Front Left / Front Right · Rear Left / Rear Right**.<br>• Header shows **`(SYNC)`** when all 4 zones are synchronized to the same temperature, or **`(4Z)`** when any zone is set independently. | — |
| **`Vent/AC Mode`** | `AUTO AC:ON` | **Active air distribution & compressor mode:**<br>• `AUTO` vs `MAN`: Whether damper flap distribution is controlled automatically by IHKA (`AUTO`) or locked to manual vent selection (`MAN`).<br>• `AC:ON` / `AC:OFF` / `MAX-AC`: Whether A/C cooling/dehumidification is enabled.<br>• `MAX DEF`: Windshield Max Defrost active.<br>• `OFF`: HVAC system switched off. | Anti-fogging windshield overrides can only occur in `AUTO`. |
| **`Fan Stp (%)`** | `S2/2 (38%)` | **Selected fan bar step (`S1..S5` in AUTO intensity, `S1..S7` in Manual) for Driver/Passenger**, followed in parentheses by the **actual Front Blower Motor PWM output (`0..100%`)**. | — |
| **`Strat L/R`** | `50/50%` | **Upper-body stratification slider setting (Driver / Passenger).**<br>• `0%` = Full Cold (Blue), **`50%` = Neutral (Center tick)**, `100%` = Full Warm (Red). | — |

---

### Row 4: Live LIN Flap Positions, Electric PTC Heater & A/C Pressure

| Tile Label | Example Display | What It Means | Diagnostic Clue |
|---|---|---|---|
| **`Def / Foot L·R`** | `15·75/75%` | **Actual stepper motor opening (`0%` = closed, `100%` = fully open) for the Windshield Defrost flap, Front Left Footwell flap, and Front Right Footwell flap.**<br>• The left and right footwell flaps are **two physically separate LIN stepper motors** (`0x801115` Left vs `0x801118` Right) and can differ whenever left/right `AUTO` intensity, sunlight, or manual vent distribution differs. | Watch `Def` when the windshield vent swings between 15°C and 30°C—if `Def` jumps from ~10% to 80%+ on its own, `IHKA` is actively running an anti-fogging cycle! |
| **`Mix Fr L/R`** | `65/65%` | **Front Left & Right Hot/Cold Temperature Blend Flaps (`0%` = 100% Cold Evaporator bypass, `100%` = 100% Hot Heater Core/PTC).** | — |
| **`Mix Rr L/R`** | `70/70%` | **Rear Left & Right Hot/Cold Temperature Blend Flaps (`0%` = Cold, `100%` = Full Hot).** | If `Mix Rr L/R` sits at `85%–100%` while `Rr Foot L/R` is already 50°C+, IHKA is intentionally commanding maximum rear heat. |
| **`PTC Heater`** | `60% 58°C` | **12V Electric PTC Auxiliary Heater PWM Duty Cycle (`0..100%`) and internal PCB/Heater temperature (`°C`).**<br>• Fires immediately on cold starts and boosts air warmth alongside the hot-water heater core. | — |
| **`A/C`** | `11b` | **A/C refrigerant high-side circuit pressure in bar.**<br>• Typical range: **4–6 bar** (A/C off in cold weather) to **8–16 bar** (compressor active). | — |

---

### Action Buttons Explained

* **`START 1Hz LOG` / `STOP LOG`:** Starts/stops writing all **57 telemetry columns** at 1 sample per second to `/Android/data/si.kalin.ihkalogger/files/ihka_log_YYYYMMDD_HHMMSS.csv` (logs from older app versions are named `g31_ihka_4zone_YYYYMMDD_HHMMSS.csv`; both appear in the share list). Unavailable values — e.g. the rear duct columns on a 2-zone car, or routines the module did not answer — are written as **empty cells**.
* **`CALIB FLAPS` (`UDS 31 01 A1 24`):**
  * **What it does:** Runs BMW's factory **"Flap Motor End-Stop Calibration"** (`KLAPPENMOTOR_KALIBRIERLAUF`) without ProTool or ISTA+: `31 01 A1 24` followed by `31 03 A1 24` status polling, sweeping all LIN stepper motors between 0% and 100%.
  * **When to use it:** Only if you ever replace a stepper motor, unplug the climate housing, or suspect a flap has lost its 0%/100% mechanical end-stops. A confirmation dialog appears before the routine starts. *(Run with ignition in **Diagnostic Mode, engine OFF**; disabled while logging.)*
* **`SHARE CSV`:** Opens a checklist of all recorded logs, newest first, labelled by date and time (`YYYY-MM-DD  HH:MM:SS`) with file size.
  * Logs **not yet shared** are shown in **bold** and pre-checked; logs already shared are shown in normal font, unchecked, and tagged `sent`.
  * **`Share (n)`** opens the Android share sheet with all checked files and marks them as sent. (The app cannot tell whether the target app actually delivered them — a log counts as sent once you tap Share.)
* **`MARK HOT BLAST` / `MARK COLD BLAST`:** Visible only while logging; see the Quick-Start Guide.

---

## 3. Desktop 5-Panel Python Analyzer (`python_tools/plot_ihka_log.py`)

Install dependencies once on your PC:
```bash
pip install -r python_tools/requirements.txt
```

Run the 5-panel diagnostic plotter on any exported CSV log:
```bash
python python_tools/plot_ihka_log.py --csv ihka_log_YYYYMMDD_HHMMSS.csv --save ihka_analysis.png
```

| Option | Default | Meaning |
|---|---|---|
| `--csv PATH` | *(required)* | CSV log exported from the app. |
| `--save PATH` | — | Also save the figure as PNG/PDF. |
| `--spike-rate R` | `2.5` | Evaporator dT/dt threshold (°C/s) for marking sensor glitches. |

The plotter detects 2-zone vs 4-zone from the data: logs from 2-zone cars have empty rear-duct columns, which are simply not plotted, and the titles show the detected zone count.

---

## License

Free for any use, including internal company use. Selling the software or including it in paid products or services is not allowed without permission. See [LICENSE](LICENSE.md) for the full terms.
