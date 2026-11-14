#!/usr/bin/env python3
"""
BMW G30/G31 IHKA (0x78) UDS Diagnostics & Live Logger for OBDLink CX (BLE 5.1).
Uses bleak over GATT Service FFF0 (Notify: FFF1, Write: FFF2) + ISO 15765-2 Extended Addressing.
"""

import argparse
import asyncio
import csv
import datetime as dt
import re
import sys
import time
from bleak import BleakClient, BleakScanner

CX_SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
CX_NOTIFY_UUID  = "0000fff1-0000-1000-8000-00805f9b34fb"
CX_WRITE_UUID   = "0000fff2-0000-1000-8000-00805f9b34fb"


class ObdLinkCxBle:
    def __init__(self, device_name: str = "OBDLink CX", address: str | None = None):
        self.device_name = device_name
        self.address = address
        self.client: BleakClient | None = None
        self._rx_buf = bytearray()
        self._prompt_event = asyncio.Event()

    def _on_notify(self, _sender, data: bytearray):
        self._rx_buf.extend(data)
        if b">" in data or b">" in self._rx_buf:
            self._prompt_event.set()

    async def connect(self, scan_timeout: float = 8.0):
        target = self.address
        if not target:
            print(f"[*] Scanning BLE for '{self.device_name}' ({scan_timeout:.0f}s)...")
            devices = await BleakScanner.discover(timeout=scan_timeout, return_adv=True)
            for dev, adv in devices.values():
                name = dev.name or adv.local_name or ""
                uuids = [u.lower() for u in (adv.service_uuids or [])]
                if self.device_name.lower() in name.lower() or CX_SERVICE_UUID in uuids:
                    target = dev
                    print(f"[+] Found {name or 'OBDLink CX'} [{dev.address}] (RSSI: {adv.rssi} dBm)")
                    break

        if not target:
            raise RuntimeError(
                "OBDLink CX not found! Unplug/replug the adapter to open its 5-min BLE pairing window, "
                "and disconnect your phone's Bluetooth."
            )

        self.client = BleakClient(target)
        await self.client.connect()
        await self.client.start_notify(CX_NOTIFY_UUID, self._on_notify)
        await asyncio.sleep(0.2)
        print("[+] BLE GATT connected & subscribed to FFF1 notifications.")

    async def disconnect(self):
        if self.client and self.client.is_connected:
            try:
                await self.client.stop_notify(CX_NOTIFY_UUID)
            except Exception:
                pass
            await self.client.disconnect()

    async def cmd(self, command: str, timeout: float = 2.0) -> str:
        assert self.client is not None
        self._rx_buf.clear()
        self._prompt_event.clear()

        payload = (command.strip() + "\r").encode("ascii")
        chunk_size = max(20, (self.client.mtu_size or 23) - 3)
        for i in range(0, len(payload), chunk_size):
            await self.client.write_gatt_char(
                CX_WRITE_UUID, payload[i : i + chunk_size], response=True
            )

        try:
            await asyncio.wait_for(self._prompt_event.wait(), timeout=timeout)
        except asyncio.TimeoutError:
            pass

        text = self._rx_buf.decode("ascii", errors="ignore")
        lines = [
            ln.strip()
            for ln in text.replace("\r", "\n").split("\n")
            if ln.strip() and ln.strip() != command.strip() and ln.strip() != ">"
        ]
        return "\n".join(lines)

    async def init_adapter(self) -> tuple[str, str]:
        elm_id = await self.cmd("ATZ", timeout=3.0)
        await self.cmd("ATE0")
        await self.cmd("ATL0")
        await self.cmd("ATS0")
        await self.cmd("ATH1")
        await self.cmd("ATAT1")
        await self.cmd("ATST32")
        stn_id = await self.cmd("STI")
        return elm_id, stn_id

    async def set_standard_obd(self):
        await self.cmd("ATCEA")
        await self.cmd("ATSP6")
        await self.cmd("ATCAF1")
        await self.cmd("ATSH7DF")
        await self.cmd("ATCRA")

    async def set_bmw_ecu(self, target_addr: int = 0x78):
        tgt = f"{target_addr:02X}"
        rx_id = f"{0x600 + target_addr:03X}"

        await self.cmd("ATSP6")
        await self.cmd("ATCAF1")
        await self.cmd("ATCERF1")
        res = await self.cmd(f"ATCEA{tgt}")
        if "?" in res:
            raise RuntimeError(f"Unexpected ATCEA rejection: {res}")

        await self.cmd("ATSH6F1")
        await self.cmd(f"ATCRA{rx_id}")
        await self.cmd("ATFCSH6F1")
        await self.cmd(f"ATFCSD{tgt}300000")
        await self.cmd("ATFCSM1")

    @staticmethod
    def parse_isotp(raw_resp: str, expected_rx_id: str = "678") -> bytes | None:
        if not raw_resp or any(e in raw_resp for e in ("NO DATA", "ERROR", "UNABLE", "?", "CAN ERROR")):
            return None

        frames = []
        for line in raw_resp.splitlines():
            clean = re.sub(r"[^0-9A-Fa-f]", "", line).upper()
            if not clean.startswith(expected_rx_id) or len(clean) < 7:
                continue
            if clean[3:5] != "F1":
                continue
            frames.append(bytes.fromhex(clean[5:]))

        if not frames:
            return None

        first = frames[0]
        pci = (first[0] >> 4) & 0x0F

        if pci == 0x0:
            length = first[0] & 0x0F
            return first[1 : 1 + length]

        if pci == 0x1:
            total_len = ((first[0] & 0x0F) << 8) | first[1]
            data = bytearray(first[2:])
            for cf in frames[1:]:
                if ((cf[0] >> 4) & 0x0F) == 0x2:
                    data.extend(cf[1:])
            return bytes(data[:total_len])

        return None

    async def uds_request(self, req_hex: str, rx_id: str = "678", timeout: float = 1.8) -> bytes | None:
        raw = await self.cmd(req_hex, timeout=timeout)
        data = self.parse_isotp(raw, expected_rx_id=rx_id)
        if not data or data[0] == 0x7F:
            return None
        return data


async def read_standard_obd(cx: ObdLinkCxBle) -> dict:
    await cx.set_standard_obd()
    vals = {}
    for pid, key in (("0105", "b58_coolant_c"), ("0146", "ambient_obd_c")):
        raw = await cx.cmd(pid, timeout=1.5)
        for line in raw.splitlines():
            clean = re.sub(r"[^0-9A-Fa-f]", "", line).upper()
            idx = clean.find("41" + pid[2:])
            if idx != -1 and len(clean) >= idx + 6:
                vals[key] = int(clean[idx + 4 : idx + 6], 16) - 40
                break
    raw_rpm = await cx.cmd("010C", timeout=1.5)
    for line in raw_rpm.splitlines():
        clean = re.sub(r"[^0-9A-Fa-f]", "", line).upper()
        idx = clean.find("410C")
        if idx != -1 and len(clean) >= idx + 8:
            vals["b58_rpm"] = int(clean[idx + 4 : idx + 8], 16) // 4
            break
    return vals


async def run_info(cx: ObdLinkCxBle):
    print("\n--- Querying BMW G31 IHKA (0x78) ---")
    await cx.set_bmw_ecu(0x78)

    sess = await cx.uds_request("1003")
    if not sess or sess[0] != 0x50:
        print("[!] No response from IHKA (0x78). Tap Start 3x quickly (foot off brake) for Diagnostic Mode!")
        return
    print(f"[+] IHKA Diagnostic Session active ({sess.hex().upper()})")

    part = await cx.uds_request("22F187")
    if part and part[0] == 0x62:
        print(f"[+] IHKA Part Number: {part[3:].decode('ascii', errors='replace').strip()}")

    dtc = await cx.uds_request("19020C", timeout=3.0)
    if dtc and dtc[0] == 0x59:
        payload = dtc[3:]
        if not payload:
            print("[+] Zero stored/active fault codes in IHKA.")
        else:
            print(f"[!] Found {len(payload) // 4} IHKA Fault Code(s):")
            for i in range(0, len(payload) - 3, 4):
                code_hex = payload[i : i + 3].hex().upper()
                status = payload[i + 3]
                print(f"    BMW DTC: 0x{code_hex}  (Status mask: 0x{status:02X})")


async def run_discover(cx: ObdLinkCxBle, ranges: list[tuple[int, int]]):
    await cx.set_bmw_ecu(0x78)
    await cx.cmd("ATST19")
    await cx.uds_request("1003")

    hits = 0
    print("\n--- Sweeping IHKA UDS Data Identifiers (0x22) ---")
    for start, end in ranges:
        print(f"[*] Range 0x{start:04X}..0x{end:04X}")
        for did in range(start, end + 1):
            if (did - start) % 64 == 0 and did > start:
                await cx.uds_request("3E00", timeout=0.5)
            resp = await cx.uds_request(f"22{did:04X}", timeout=0.6)
            if resp and len(resp) > 3 and resp[0] == 0x62:
                vb = resp[3:]
                hints = []
                if len(vb) == 1:
                    b0 = vb[0]
                    hints.extend([f"b-40={b0-40:.1f}C", f"0.5b-40={0.5*b0-40:.1f}C", f"pct={b0}%" if b0 <= 100 else f"raw={b0}"])
                elif len(vb) == 2:
                    u16 = (vb[0] << 8) | vb[1]
                    s16 = int.from_bytes(vb, "big", signed=True)
                    hints.extend([f"0.1u-40={0.1*u16-40:.1f}C", f"0.1s={0.1*s16:.1f}C", f"0.01s={0.01*s16:.2f}"])
                print(f"  [HIT] 0x{did:04X} ({len(vb)}B): {vb.hex().upper():<16} | {', '.join(hints)}")
                hits += 1
    await cx.cmd("ATST32")
    print(f"[+] Sweep finished: {hits} active DIDs responded.")


async def run_monitor(cx: ObdLinkCxBle, dids: list[int], csv_path: str, interval: float = 1.0):
    print(f"\n[*] Logging {len(dids)} IHKA DIDs + B58 Coolant/Ambient/RPM -> {csv_path} (Ctrl+C to stop)")
    headers = ["timestamp", "elapsed_s", "b58_coolant_c", "ambient_obd_c", "b58_rpm", "event_marker"]
    for d in dids:
        headers.extend([f"DID_{d:04X}_hex", f"DID_{d:04X}_1b_m40", f"DID_{d:04X}_u16_01_m40", f"DID_{d:04X}_s16_01"])

    t0 = time.monotonic()
    with open(csv_path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(headers)

        while True:
            t_loop = time.monotonic()
            now = dt.datetime.now().isoformat(timespec="milliseconds")
            elapsed = round(t_loop - t0, 2)

            obd_t = await read_standard_obd(cx)
            await cx.set_bmw_ecu(0x78)
            await cx.uds_request("3E00", timeout=0.6)

            row = [
                now,
                elapsed,
                obd_t.get("b58_coolant_c", ""),
                obd_t.get("ambient_obd_c", ""),
                obd_t.get("b58_rpm", ""),
                "",
            ]
            summary = [
                f"t={elapsed:6.1f}s",
                f"B58={obd_t.get('b58_coolant_c', '?')}C",
                f"Amb={obd_t.get('ambient_obd_c', '?')}C",
                f"RPM={obd_t.get('b58_rpm', '?')}",
            ]

            for d in dids:
                resp = await cx.uds_request(f"22{d:04X}", timeout=0.8)
                if resp and len(resp) > 3 and resp[0] == 0x62:
                    vb = resp[3:]
                    hx = vb.hex().upper()
                    c_1b = round(vb[0] - 40, 1)
                    c_u16 = round(0.1 * ((vb[0] << 8) | vb[1]) - 40, 2) if len(vb) >= 2 else ""
                    c_s16 = round(0.1 * int.from_bytes(vb[:2], "big", signed=True), 2) if len(vb) >= 2 else ""
                    row.extend([hx, c_1b, c_u16, c_s16])
                    summary.append(f"0x{d:04X}={hx}")
                else:
                    row.extend(["", "", "", ""])
                    summary.append(f"0x{d:04X}=NA")

            writer.writerow(row)
            f.flush()
            print(" | ".join(summary))

            sleep_rem = interval - (time.monotonic() - t_loop)
            if sleep_rem > 0.02:
                await asyncio.sleep(sleep_rem)


async def async_main():
    parser = argparse.ArgumentParser(description="BMW G31 IHKA BLE Logger for OBDLink CX")
    parser.add_argument("--addr", help="Optional BLE MAC address (auto-discovers 'OBDLink CX' if omitted)")
    parser.add_argument("--info", action="store_true", help="Read STN firmware, IHKA part #, and BMW DTC codes")
    parser.add_argument("--discover", action="store_true", help="Scan IHKA UDS 0x22 DIDs (0xD800-0xDBFF, 0x4000-0x41FF)")
    parser.add_argument("--range", nargs=2, metavar=("START_HEX", "END_HEX"), help="Custom hex DID sweep range, e.g. D000 D7FF")
    parser.add_argument("--monitor", nargs="+", help="Hex DIDs to log continuously (e.g. D901 D904 D912)")
    parser.add_argument("--out", default="g31_ihka_ble_log.csv", help="CSV output path")
    args = parser.parse_args()

    cx = ObdLinkCxBle(address=args.addr)
    try:
        await cx.connect()
        elm_id, stn_id = await cx.init_adapter()
        print(f"[+] Adapter Ready -> {elm_id} | Firmware: {stn_id}")

        obd_t = await read_standard_obd(cx)
        print(
            f"[+] B58 OBD Baseline -> Coolant: {obd_t.get('b58_coolant_c', 'N/A')}°C | "
            f"Ambient: {obd_t.get('ambient_obd_c', 'N/A')}°C | RPM: {obd_t.get('b58_rpm', 'N/A')}"
        )

        if args.info:
            await run_info(cx)

        if args.discover or args.range:
            ranges = [(int(args.range[0], 16), int(args.range[1], 16))] if args.range else [(0xD800, 0xDBFF), (0x4000, 0x41FF)]
            await run_discover(cx, ranges)

        if args.monitor:
            await run_monitor(cx, [int(x, 16) for x in args.monitor], args.out)

    except KeyboardInterrupt:
        print("\n[*] Stopped by user.")
    finally:
        await cx.disconnect()


if __name__ == "__main__":
    try:
        asyncio.run(async_main())
    except KeyboardInterrupt:
        sys.exit(0)
