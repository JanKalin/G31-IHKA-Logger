#!/usr/bin/env python3
"""
Synchronized 3-panel diagnostic plotter for BMW G31 IHKA CSV logs
(compatible with both the Android G31IhkaLogger app and ihka_cx_ble.py).
Plots B58 Coolant, Ambient, B58 RPM (twin axis), IHKA Thermistors with dT/dt spike alerts,
LIN Blend/Stratification Flap Positions (0-100%), and HOT_SWING / COLD_SWING markers.
"""

import argparse
import csv
import re
import sys
from pathlib import Path
import numpy as np
import matplotlib.pyplot as plt


CHANNEL_MAP: dict[str, tuple[str, str, str]] = {
    # Examples (uncomment and replace hex IDs after running SWEEP / --discover):
    # "D901": ("Evaporator Temp", "temp", "u16_01_m40"),
    # "D904": ("Footwell Vent L", "temp", "u16_01_m40"),
    # "D905": ("Footwell Vent R", "temp", "u16_01_m40"),
    # "D910": ("Blend Flap L",    "flap", "pct_raw"),
}


def decode_hex_series(hex_list: list[str], formula: str) -> np.ndarray:
    out = np.full(len(hex_list), np.nan, dtype=float)
    for i, hx in enumerate(hex_list):
        hx = hx.strip()
        if not hx:
            continue
        try:
            b = bytes.fromhex(hx)
        except ValueError:
            continue
        if not b:
            continue

        if formula == "1b_m40":
            out[i] = b[0] - 40.0
        elif formula == "0.5b_m40":
            out[i] = 0.5 * b[0] - 40.0
        elif formula == "pct_raw":
            out[i] = float(b[0])
        elif formula == "pct_255":
            out[i] = b[0] * 100.0 / 255.0
        elif len(b) >= 2:
            u16 = (b[0] << 8) | b[1]
            s16 = int.from_bytes(b[:2], "big", signed=True)
            if formula == "u16_01_m40":
                out[i] = 0.1 * u16 - 40.0
            elif formula == "s16_01":
                out[i] = 0.1 * s16
            elif formula == "s16_001":
                out[i] = 0.01 * s16
    return out


def auto_classify_did(did_hex: str, hex_series: list[str]) -> tuple[str, str, np.ndarray] | None:
    valid_bytes = [bytes.fromhex(h) for h in hex_series if h.strip() and h.strip() != "ERR"]
    if not valid_bytes:
        return None

    byte_len = len(valid_bytes[0])

    if byte_len == 1:
        raw = np.array([b[0] for b in valid_bytes], dtype=float)
        if np.nanmin(raw) >= 0 and np.nanmax(raw) <= 100 and np.nanstd(raw) > 0.0:
            return ("flap", f"0x{did_hex} [pct_raw]", decode_hex_series(hex_series, "pct_raw"))
        t1 = raw - 40.0
        if -25.0 <= np.nanmedian(t1) <= 85.0:
            return ("temp", f"0x{did_hex} [b-40]", decode_hex_series(hex_series, "1b_m40"))
        t05 = 0.5 * raw - 40.0
        if -25.0 <= np.nanmedian(t05) <= 85.0:
            return ("temp", f"0x{did_hex} [0.5b-40]", decode_hex_series(hex_series, "0.5b_m40"))

    elif byte_len >= 2:
        arr_u = decode_hex_series(hex_series, "u16_01_m40")
        if -25.0 <= np.nanmedian(arr_u) <= 90.0 and np.nanmax(arr_u) < 130.0:
            return ("temp", f"0x{did_hex} [0.1u-40]", arr_u)
        arr_s = decode_hex_series(hex_series, "s16_01")
        if -25.0 <= np.nanmedian(arr_s) <= 90.0 and np.nanmax(arr_s) < 130.0:
            return ("temp", f"0x{did_hex} [0.1s]", arr_s)
        arr_s2 = decode_hex_series(hex_series, "s16_001")
        if -25.0 <= np.nanmedian(arr_s2) <= 90.0:
            return ("temp", f"0x{did_hex} [0.01s]", arr_s2)

    return None


def parse_cli_map(items: list[str]) -> dict[str, tuple[str, str, str]]:
    out = dict(CHANNEL_MAP)
    for item in items:
        parts = item.split(":")
        if len(parts) != 3:
            raise ValueError(f"Invalid --map '{item}'. Expected DID_HEX:Label:Formula")
        did, label, formula = parts[0].upper().removeprefix("0X"), parts[1], parts[2]
        panel = "flap" if "pct" in formula else "temp"
        out[did] = (label, panel, formula)
    return out


def load_log(csv_path: Path):
    with csv_path.open("r", newline="") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
        fieldnames = reader.fieldnames or []

    if not rows:
        print(f"[!] CSV file '{csv_path}' contains no data rows.", file=sys.stderr)
        sys.exit(1)

    def to_float_arr(col: str) -> np.ndarray:
        out = np.full(len(rows), np.nan, dtype=float)
        for i, r in enumerate(rows):
            val = (r.get(col) or "").strip()
            if val:
                try:
                    out[i] = float(val)
                except ValueError:
                    pass
        return out

    t = to_float_arr("elapsed_s")
    coolant = to_float_arr("b58_coolant_c")
    ambient = to_float_arr("ambient_obd_c")
    rpm = to_float_arr("b58_rpm")
    markers = [(r.get("event_marker") or "").strip() for r in rows]

    did_hex_cols = {}
    for col in fieldnames:
        m = re.match(r"^DID_([0-9A-Fa-f]{4})_hex$", col)
        if m:
            did_id = m.group(1).upper()
            did_hex_cols[did_id] = [r.get(col, "") for r in rows]

    return t, coolant, ambient, rpm, markers, did_hex_cols


def detect_spikes(t: np.ndarray, y: np.ndarray, threshold_c_per_s: float = 3.0) -> np.ndarray:
    valid = np.isfinite(t) & np.isfinite(y)
    if np.count_nonzero(valid) < 3:
        return np.array([], dtype=int)

    dt_arr = np.diff(t)
    dy_arr = np.diff(y)
    with np.errstate(divide="ignore", invalid="ignore"):
        rate = np.abs(dy_arr / np.where(dt_arr > 0.05, dt_arr, np.nan))
    return np.where(rate >= threshold_c_per_s)[0] + 1


def main():
    parser = argparse.ArgumentParser(description="Plot BMW G31 IHKA BLE CSV Log")
    parser.add_argument("--csv", required=True, help="Path to CSV log file")
    parser.add_argument(
        "--map",
        nargs="*",
        default=[],
        help="Override DID mapping: HEX:Label:Formula (1b_m40, 0.5b_m40, u16_01_m40, s16_01, s16_001, pct_raw, pct_255)",
    )
    parser.add_argument("--spike-rate", type=float, default=3.0, help="dT/dt spike alert threshold in °C/s (default: 3.0)")
    parser.add_argument("--save", help="Optional PNG/PDF filename to export plot")
    args = parser.parse_args()

    csv_path = Path(args.csv)
    if not csv_path.exists():
        print(f"[!] File not found: {csv_path}", file=sys.stderr)
        sys.exit(1)

    cmap = parse_cli_map(args.map)
    t, coolant, ambient, rpm, markers, did_hex_cols = load_log(csv_path)
    t_min = t / 60.0

    temp_traces: list[tuple[str, np.ndarray]] = []
    flap_traces: list[tuple[str, np.ndarray]] = []

    print(f"\n--- Channel Summary ({len(t)} samples, duration {np.nanmax(t):.1f}s) ---")
    for did_id, hx_list in did_hex_cols.items():
        if did_id in cmap:
            label, panel, formula = cmap[did_id]
            arr = decode_hex_series(hx_list, formula)
        else:
            res = auto_classify_did(did_id, hx_list)
            if res is None:
                print(f"  [SKIP] DID 0x{did_id}: constant or unrecognized bitfield")
                continue
            panel, label, arr = res

        finite = arr[np.isfinite(arr)]
        if len(finite) == 0:
            continue

        unit = "%" if panel == "flap" else "°C"
        print(
            f"  [{panel.upper():4s}] {label:<24s} | min={np.min(finite):6.1f}{unit}  "
            f"max={np.max(finite):6.1f}{unit}  std={np.std(finite):5.2f}{unit}"
        )
        if panel == "flap":
            flap_traces.append((label, arr))
        else:
            temp_traces.append((label, arr))

    fig, (ax_eng, ax_ihka, ax_flap) = plt.subplots(
        3, 1, figsize=(13, 9.5), sharex=True, gridspec_kw={"height_ratios": [1.05, 1.85, 1.1]}
    )
    fig.suptitle(f"BMW G31 540i IHKA Climate Control Diagnostic Log — {csv_path.name}", fontsize=13, fontweight="bold")

    # Panel 1: B58 Coolant, Ambient, and optional RPM twin axis
    if np.any(np.isfinite(coolant)):
        ax_eng.plot(t_min, coolant, color="#d62728", lw=1.8, label="B58 Engine Coolant (°C)")
    if np.any(np.isfinite(ambient)):
        ax_eng.plot(t_min, ambient, color="#1f77b4", lw=1.5, ls="--", label="OBD Ambient Temp (°C)")
    ax_eng.axhline(0.0, color="gray", lw=0.8, ls=":")
    ax_eng.set_ylabel("Engine / Amb (°C)")
    ax_eng.set_title("1. B58 Coolant Supply, Ambient & Engine RPM", fontsize=10, loc="left")
    ax_eng.grid(True, alpha=0.3)

    if np.any(np.isfinite(rpm)):
        ax_rpm = ax_eng.twinx()
        ax_rpm.plot(t_min, rpm, color="#ff9800", lw=1.1, alpha=0.55, label="B58 RPM")
        ax_rpm.set_ylabel("Engine RPM", color="#e65100")
        ax_rpm.tick_params(axis="y", labelcolor="#e65100")
        lines1, labels1 = ax_eng.get_legend_handles_labels()
        lines2, labels2 = ax_rpm.get_legend_handles_labels()
        ax_eng.legend(lines1 + lines2, labels1 + labels2, loc="upper right", fontsize=8)
    else:
        ax_eng.legend(loc="upper right", fontsize=8.5)

    # Panel 2: IHKA Evaporator & Vent Thermistors
    ax_ihka.axhspan(0.0, 3.5, color="#00b4d8", alpha=0.14, label="Evap Freeze-Protection Zone (0–3.5°C)")
    ax_ihka.axhline(40.0, color="#ff7f0e", lw=0.9, ls=":", alpha=0.7, label="40°C Footwell Overheat Ref")

    for label, arr in temp_traces:
        ax_ihka.plot(t_min, arr, lw=1.6, label=label)
        spikes = detect_spikes(t, arr, threshold_c_per_s=args.spike_rate)
        if len(spikes) > 0:
            ax_ihka.scatter(
                t_min[spikes],
                arr[spikes],
                color="red",
                edgecolors="black",
                s=45,
                zorder=6,
                label=f"{label} Spike (≥{args.spike_rate:.1f}°C/s)",
            )
            print(f"  [ALERT] {len(spikes)} erratic dT/dt spike(s) detected on {label}!")

    ax_ihka.set_ylabel("IHKA Temps (°C)")
    ax_ihka.set_title("2. IHKA Evaporator & Vent Thermistors (Red dots = thermistor glitch/dropout)", fontsize=10, loc="left")
    ax_ihka.grid(True, alpha=0.3)
    if temp_traces:
        ax_ihka.legend(loc="upper right", ncol=2, fontsize=8)

    # Panel 3: LIN Blend & Stratification Flaps
    for label, arr in flap_traces:
        ax_flap.plot(t_min, arr, lw=1.6, drawstyle="steps-post", label=label)
        if np.any(arr > 100.0):
            print(f"  [ALERT] {label} reported value > 100% (uncalibrated LIN flap end-stop!)")

    ax_flap.set_ylim(-5, 110)
    ax_flap.set_ylabel("Flap Pos (%)")
    ax_flap.set_xlabel("Elapsed Drive Time (minutes)")
    ax_flap.set_title("3. LIN Blend / Stratification Flap Actuators (0–100%)", fontsize=10, loc="left")
    ax_flap.grid(True, alpha=0.3)
    if flap_traces:
        ax_flap.legend(loc="upper right", ncol=2, fontsize=8)
    else:
        ax_flap.text(
            0.5, 0.5, "No 0–100% flap DIDs mapped yet (use --map HEX:Label:pct_raw)",
            ha="center", va="center", transform=ax_flap.transAxes, color="gray", fontsize=9,
        )

    # Overlay Cockpit HOT_SWING / COLD_SWING event markers across all 3 panels
    hot_labeled = False
    cold_labeled = False
    for idx, tag in enumerate(markers):
        if not tag or not np.isfinite(t_min[idx]):
            continue
        is_hot = "HOT" in tag.upper()
        clr = "#ff5722" if is_hot else "#0288d1"
        for ax in (ax_eng, ax_ihka, ax_flap):
            lbl = None
            if ax is ax_ihka:
                if is_hot and not hot_labeled:
                    lbl = "Marked HOT Blast"
                    hot_labeled = True
                elif not is_hot and not cold_labeled:
                    lbl = "Marked COLD Blast"
                    cold_labeled = True
            ax.axvline(t_min[idx], color=clr, ls="-.", lw=1.3, alpha=0.8, label=lbl)

    if hot_labeled or cold_labeled:
        ax_ihka.legend(loc="upper right", ncol=2, fontsize=8)

    plt.tight_layout()
    if args.save:
        plt.savefig(args.save, dpi=180)
        print(f"[+] Saved plot to {args.save}")
    plt.show()


if __name__ == "__main__":
    main()
