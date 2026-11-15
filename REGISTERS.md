# IHKA Register Map

Where each dashboard tile of the [G30/G31 IHKA Logger](README.md) gets its data. All requests go to the IHKA climate module (`IHKA_G11`, diagnostic address `0x78`, CAN ID `0x6F1` → `0x678`) except the 12V bus voltage, which comes from the adapter itself.

* `DID 0xXXXX` = UDS *ReadDataByIdentifier* request `22 XX XX`.
* `Routine 31 03 A1 XX` = UDS *RoutineControl – request results* for routine `0xA1XX`.
* Byte numbers are counted from the first data byte after the response header (`62 XX XX` or `71 03 A1 XX`).

---

## Row 1: Global Vehicle & Environmental Sensors

| Tile | Source | Bytes | Result name | Formula |
|---|---|---|---|---|
| **`12V Bus`** | OBDLink CX `ATRV` (OBD pin 16 ADC) | — | — | Direct reading, `0.01V` resolution |
| **`Evap`** | `DID 0xD8D7` | `0` | `STAT_TEMP_VERDAMPFER_WERT` | `raw / 5.0 - 10.0` (°C) |
| **`Cabin`** | `DID 0xD8D7` | `10` | `STAT_TEMP_INNENRAUM_UNGEDAEMPFT_WERT` | `int8` (°C) |
| **`Glass/RH`** | `DID 0xD8D9` | `3` (glass) & `2` (RH) | `STAT_BUS_IN_RLBS_TEMP_FRONTSCHEIBE_WERT` & `…_LUFTFEUCHTIGKEIT` | Glass: `raw / 2.0 - 40.0` (°C)<br>RH: `raw / 2.0` (%) |
| **`Coolant`** | `DID 0xD8D9` | `8..9` | `STAT_BUS_IN_KUEHLMITTEL_MOTOR_TEMP_WERT` | `int16_be` (°C) |

## Row 2: Outlet Duct Temperatures

All duct temperatures come from one multi-frame response to `DID 0xD8D7` (`RES_0xD8D7_D`), as signed 1°C integers (`int8`). On 2-zone cars the rear bytes `6..9` are unpopulated and read `0x80` or `0x00`; a value of `0x80` always means "no sensor / invalid".

| Tile | Bytes (L / R) | Result name |
|---|---|---|
| **`Fr Vent L/R`** | `1` / `2` | `STAT_TEMP_BELUEFTUNG_LINKS/RECHTS_WERT` |
| **`Fr Foot L/R`** | `3` / `4` | `STAT_TEMP_FUSSRAUM_LINKS/RECHTS_WERT` |
| **`Rr Vent L/R`** | `6` / `7` | `STAT_TEMP_BELUEFTUNG_HINTEN_LINKS/RECHTS_WERT` |
| **`Rr Foot L/R`** | `8` / `9` | `STAT_TEMP_FUSSRAUM_HINTEN_LINKS/RECHTS_WERT` |

## Row 3: Aircon Panel Settings & Operating State

| Tile | Source | Bytes | Result block |
|---|---|---|---|
| **`Set F/R (SYNC/4Z)`** | `Routine 31 03 A1 2A` | `1..4` (FL, FR, RL, RR setpoints) | `RES_0xA12A_R` |
| **`Vent/AC Mode`** | `DID 0xD8DB` | `7, 8, 9, 11, 12, 15` | `RES_0xD8DB_D` |
| **`Fan Stp (%)`** | `DID 0xD8DB` + `Routine 31 03 A1 28` | `16, 17` (fan steps L/R) + `0` (front blower PWM %) | `RES_0xD8DB_D`, `RES_0xA128_R` |
| **`Strat L/R`** | `DID 0xD8D8` | `1, 2` | `RES_0xD8D8_D` |

## Row 4: LIN Flap Positions, Electric PTC Heater & A/C Pressure

| Tile | Source | Bytes | Result name / formula |
|---|---|---|---|
| **`Def / Foot L·R`** | `Routine 31 03 A1 22` | `3` (defrost) · `11` (footwell L) / `12` (footwell R) | Flap position `0..100%` |
| **`Mix Fr L/R`** | `Routine 31 03 A1 22` | `16` / `17` | `STAT_ISTPOSITION_MISCHLUFT_LINKS/RECHTS_WERT` |
| **`Mix Rr L/R`** | `Routine 31 03 A1 22` | `19` / `20` | `STAT_ISTPOSITION_MISCHLUFT_HINTEN_LINKS/RECHTS_WERT` |
| **`PTC Heater`** | `Routine 31 03 A1 2B` | `0` (duty) & `3` (temperature) | `RES_0xA12B_R`; duty `raw / 2.0` (%), temperature `raw - 40` (°C) |
| **`A/C`** | `DID 0xD8D9` | `7` | `STAT_BUS_IN_KAELTEKREISLAUF_DRUCK_WERT` (bar) |

## Service Routine

| Button | Request | Meaning |
|---|---|---|
| **`CALIB FLAPS`** | `31 01 A1 24` (start), then `31 03 A1 24` (poll status) | Flap motor end-stop calibration (`KLAPPENMOTOR_KALIBRIERLAUF`) |
