# BMW G31 540i — IHKA Climate Control BLE Logger & Plotter

Complete Android Studio (Kotlin + Jetpack Compose) project and companion Python plotting suite for diagnosing ~0°C cabin temperature oscillations on a BMW G30/G31 540i using an **OBDLink CX (BLE 5.1)** adapter.

## Directory Layout

```text
G31IhkaLogger/
├── settings.gradle.kts
├── build.gradle.kts                  # AGP 8.7.2 + Kotlin 2.0.21 + Compose Compiler
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts              # minSdk 31, targetSdk 35, Jetpack Compose
│   └── src/main/
│       ├── AndroidManifest.xml       # BLE scan (neverForLocation) + FileProvider
│       ├── res/
│       │   ├── drawable/ic_launcher.xml
│       │   ├── values/strings.xml
│       │   └── xml/file_paths.xml
│       └── java/com/g31/ihkalogger/
│           ├── ObdLinkBleManager.kt  # GATT FFF0/FFF1/FFF2 + ISO-TP Extended (0x6F1 -> 0x78)
│           └── MainActivity.kt       # ViewModel + 1Hz CSV Logger + Compose Cockpit UI
└── python_tools/
    ├── plot_ihka_log.py              # 3-panel Matplotlib analyzer (RPM + dT/dt spikes + markers)
    ├── ihka_cx_ble.py                # Optional desktop BLE logger (bleak)
    └── requirements.txt
```

## Quick Start

1. **Android Studio:** Choose **File -> Open** and select the unzipped `G31IhkaLogger` directory. Android Studio will download Gradle 8.9 automatically via `gradle-wrapper.properties`, sync dependencies, and deploy straight to your phone.
2. **In the G31:**
   - Unplug & replug the **OBDLink CX** to open its 5-minute BLE pairing window.
   - Press **Start/Stop** 3 times rapidly (foot off brake) to enter **Diagnostic Mode**.
   - Tap **Connect CX** -> accept BLE pairing -> tap **DTCs** -> tap **SWEEP** to populate your G31's live IHKA DIDs.
   - Start the engine, tap **START LOG**, and use **MARK HOT BLAST** / **MARK COLD BLAST** whenever the cabin temperature oscillates.
   - Tap **STOP LOG** -> **SHARE** to export `g31_ihka_ble_log_YYYYMMDD_HHMMSS.csv`.
3. **Desktop Plot:**
   ```bash
   cd python_tools
   pip install -r requirements.txt
   python plot_ihka_log.py --csv g31_ihka_ble_log_YYYYMMDD_HHMMSS.csv
   ```
