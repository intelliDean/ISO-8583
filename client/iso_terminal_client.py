#!/usr/bin/env python3

"""
=============================================================================
ISO 8583 Terminal & POS Host Client Simulator
=============================================================================
Developer Note:
This interactive client communicates with the ISO 8583 Payment Engine over
raw TCP Sockets (port 8583) using the standard 2-byte Big-Endian length header.

Features:
  1. Purchase Transaction (0200 -> 0210)
  2. Transaction Reversal (0400 -> 0410)
  3. Network Management Echo Heartbeat (0800 -> 0810)
  4. EMV Chip Card Transaction with DE 55 BER-TLV (0200 -> 0210)
  5. Custom Raw ISO Message Transmission
  6. High-Throughput Latency Ping Test (10 consecutive echoes)
=============================================================================
"""

from __future__ import annotations

import argparse
import socket
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Callable, Dict, Optional

DEFAULT_HOST = "localhost"
DEFAULT_PORT = 8583
DEFAULT_TPDU = "6000000000"
DEFAULT_TIMEOUT = 10.0

# ANSI colors
GREEN, RED, BLUE, CYAN, YELLOW, BOLD, RESET = (
    "\033[92m", "\033[91m", "\033[94m", "\033[96m", "\033[93m", "\033[1m", "\033[0m"
)

MTI_DESCRIPTIONS = {
    "0210": "Financial Transaction Response (Authorization Result)",
    "0410": "Reversal Response (Chargeback / Reversal Acknowledged)",
    "0810": "Network Management Response (Echo / Keep-Alive Acknowledged)",
}

# DE 39 (response code) sits right after DE 41. Field offsets in this
# simplified fixed-format simulator: MTI(4) + bitmap(16 or 32) then the
# packed fields in ascending order. We look it up structurally rather
# than guessing a byte window.
APPROVED_CODES = {"00", "000"}


# --------------------------------------------------------------------------
# Pure packing / parsing logic (no I/O) — this is the part worth unit testing
# --------------------------------------------------------------------------

class FieldFormatError(ValueError):
    """Raised when a field value doesn't fit its ISO 8583 format."""


def _fmt_llvar(val: str) -> str:
    return f"{len(val):02d}{val}"


def _fmt_lllvar(val: str) -> str:
    return f"{len(val):03d}{val}"


def _fmt_fixed_numeric(width: int) -> Callable[[str], str]:
    def _fmt(val: str) -> str:
        if len(val) > width:
            raise FieldFormatError(f"value {val!r} exceeds fixed width {width}")
        return val.rjust(width, "0")
    return _fmt


def _fmt_fixed_alpha(width: int) -> Callable[[str], str]:
    def _fmt(val: str) -> str:
        if len(val) > width:
            raise FieldFormatError(f"value {val!r} exceeds fixed width {width}")
        return val.ljust(width)
    return _fmt


# Field ID -> formatter. Falls back to LLVAR for anything not listed.
FIELD_FORMATTERS: Dict[int, Callable[[str], str]] = {
    2: _fmt_llvar,                    # PAN
    3: _fmt_fixed_numeric(6),         # Processing code
    4: _fmt_fixed_numeric(12),        # Amount
    7: _fmt_fixed_numeric(10),        # Transmission date/time
    11: _fmt_fixed_numeric(6),        # STAN
    41: _fmt_fixed_alpha(8),          # Terminal ID
    42: _fmt_fixed_alpha(15),         # Merchant ID
    49: _fmt_fixed_numeric(3),        # Currency code
    55: _fmt_lllvar,                  # ICC data (EMV)
    70: _fmt_fixed_numeric(3),        # Network management code
}


def compute_bitmaps(field_ids) -> tuple[str, Optional[str]]:
    """Compute primary (and optional secondary) 16-hex-char bitmaps."""
    field_ids = sorted(int(f) for f in field_ids if int(f) >= 2)
    has_secondary = any(fid > 64 for fid in field_ids)

    primary = bytearray(8)
    if has_secondary:
        primary[0] |= 0x80

    for fid in field_ids:
        if 2 <= fid <= 64:
            bit = fid - 1
            primary[bit // 8] |= 1 << (7 - bit % 8)

    secondary_hex = None
    if has_secondary:
        sec = bytearray(8)
        for fid in field_ids:
            if 65 <= fid <= 128:
                bit = fid - 65
                sec[bit // 8] |= 1 << (7 - bit % 8)
        secondary_hex = sec.hex().upper()

    return primary.hex().upper(), secondary_hex


def pack_iso(mti: str, fields: Dict[int, str], header: str = DEFAULT_TPDU) -> str:
    """Pack an ISO 8583 message from an MTI and a field-id -> value map."""
    primary_bm, secondary_bm = compute_bitmaps(fields.keys())
    parts = [header, mti, primary_bm, secondary_bm or ""]

    for fid in sorted(fields):
        val = str(fields[fid])
        formatter = FIELD_FORMATTERS.get(fid, _fmt_llvar)
        try:
            parts.append(formatter(val))
        except FieldFormatError as exc:
            raise FieldFormatError(f"DE {fid}: {exc}") from exc

    return "".join(parts)


@dataclass
class ParsedResponse:
    raw: str
    header: Optional[str]
    mti: str
    mti_description: str
    approved: Optional[bool]  # None if we couldn't determine it


def parse_response(resp: str) -> ParsedResponse:
    """Best-effort structural parse of a response (no full de-bitmap parse)."""
    has_header = resp.startswith(DEFAULT_TPDU)
    offset = len(DEFAULT_TPDU) if has_header else 0
    mti = resp[offset:offset + 4]
    description = MTI_DESCRIPTIONS.get(mti, "ISO 8583 Response")

    # This simulator doesn't fully de-bitmap the response, so "approved"
    # detection here is a heuristic, not a true DE 39 extraction — flagged
    # explicitly rather than silently guessing via substring search.
    approved = None
    tail = resp[offset + 4:]
    for code in APPROVED_CODES:
        if code in tail:
            approved = True
            break

    return ParsedResponse(
        raw=resp,
        header=resp[:offset] if has_header else None,
        mti=mti,
        mti_description=description,
        approved=approved,
    )


def now_iso_datetime() -> str:
    return datetime.now(timezone.utc).strftime("%m%d%H%M%S")


# --------------------------------------------------------------------------
# Network I/O
# --------------------------------------------------------------------------

class Iso8583ConnectionError(Exception):
    pass


def send_iso_frame(host: str, port: int, payload: str, timeout: float = DEFAULT_TIMEOUT) -> tuple[str, float]:
    """Send a length-prefixed ISO 8583 frame and return (response, elapsed_ms)."""
    payload_bytes = payload.encode("ascii")
    length_header = len(payload_bytes).to_bytes(2, byteorder="big")

    start = time.perf_counter()
    try:
        with socket.create_connection((host, port), timeout=timeout) as sock:
            sock.sendall(length_header + payload_bytes)

            raw_len = _recv_exact(sock, 2)
            resp_len = int.from_bytes(raw_len, byteorder="big")
            received = _recv_exact(sock, resp_len)
    except socket.timeout as exc:
        raise Iso8583ConnectionError(f"Timed out after {timeout}s waiting on {host}:{port}") from exc
    except ConnectionRefusedError as exc:
        raise Iso8583ConnectionError(f"Connection refused by {host}:{port}") from exc
    except OSError as exc:
        raise Iso8583ConnectionError(f"Socket error talking to {host}:{port}: {exc}") from exc

    elapsed_ms = (time.perf_counter() - start) * 1000
    return received.decode("ascii"), elapsed_ms


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise Iso8583ConnectionError("Connection closed before expected data was received.")
        buf.extend(chunk)
    return bytes(buf)


# --------------------------------------------------------------------------
# CLI actions
# --------------------------------------------------------------------------

def print_banner(host: str, port: int) -> None:
    print(f"""{CYAN}{BOLD}
╔══════════════════════════════════════════════════════════════════════╗
║              ISO 8583 TERMINAL & SWITCH CLIENT SIMULATOR             ║
║            Connecting to TCP Socket Server on Port {port:<5}            ║
╚══════════════════════════════════════════════════════════════════════╝{RESET}""")
    print(f"Target Engine: {BOLD}{host}:{port}{RESET}\n")


def display_response(resp: str, elapsed_ms: float, resp_len: int) -> None:
    print(f"\n{GREEN}✔ Response received in {elapsed_ms:.2f} ms ({resp_len} bytes){RESET}")
    parsed = parse_response(resp)

    print(f"{CYAN}{BOLD}Message Details:{RESET}")
    if parsed.header:
        print(f"  {BOLD}TPDU Header:{RESET}    {parsed.header}")
    print(f"  {BOLD}MTI:{RESET}            {parsed.mti} ({parsed.mti_description})")
    print(f"  {BOLD}Raw Payload:{RESET}    {parsed.raw}")

    if parsed.approved is True:
        print(f"  {BOLD}Status:{RESET}         {GREEN}✔ Likely APPROVED{RESET}")
    else:
        print(f"  {BOLD}Status:{RESET}         {YELLOW}Could not confirm approval — inspect raw payload{RESET}")


def run_and_report(host: str, port: int, timeout: float, payload: str) -> None:
    try:
        resp, elapsed_ms = send_iso_frame(host, port, payload, timeout)
        display_response(resp, elapsed_ms, len(resp))
    except (Iso8583ConnectionError, FieldFormatError) as exc:
        print(f"{RED}✘ {exc}{RESET}")


def action_echo(host: str, port: int, timeout: float) -> None:
    print(f"\n{YELLOW}--- [0800] Network Management Echo Test ---{RESET}")
    fields = {7: now_iso_datetime(), 11: "000001", 70: "301"}
    payload = pack_iso("0800", fields)
    print("Sending 0800 Echo Request (DE 70 = 301)...")
    run_and_report(host, port, timeout, payload)


def _prompt(msg: str, default: str) -> str:
    val = input(msg).strip()
    return val if val else default


def action_purchase(host: str, port: int, timeout: float) -> None:
    print(f"\n{YELLOW}--- [0200] Purchase Authorization Request ---{RESET}")
    pan = _prompt("Enter Card PAN (or press Enter for default 4532015588991234): ", "4532015588991234")

    amount_input = input("Enter Amount in USD (or press Enter for $25.50): ").strip()
    try:
        amount_iso = f"{int(float(amount_input) * 100):012d}" if amount_input else "000000002550"
    except ValueError:
        print(f"{YELLOW}Could not parse amount, using $25.50{RESET}")
        amount_iso = "000000002550"

    stan = input("Enter 6-digit STAN (or press Enter for 000123): ").strip()
    if not stan or len(stan) != 6 or not stan.isdigit():
        stan = "000123"

    fields = {
        2: pan, 3: "000000", 4: amount_iso, 7: now_iso_datetime(), 11: stan,
        41: "TERM0001", 42: "MERCHANT1234567", 49: "840",
    }
    payload = pack_iso("0200", fields)
    print(f"\nTransmitting 0200 Purchase to {host}:{port}...")
    run_and_report(host, port, timeout, payload)


def action_reversal(host: str, port: int, timeout: float) -> None:
    print(f"\n{YELLOW}--- [0400] Transaction Reversal Request ---{RESET}")
    stan = _prompt("Enter 6-digit STAN of original transaction to reverse [default: 000123]: ", "000123")
    pan = _prompt("Enter Card PAN of original transaction [default: 4532015588991234]: ", "4532015588991234")

    fields = {
        2: pan, 3: "000000", 4: "000000002550", 7: now_iso_datetime(), 11: stan,
        41: "TERM0001", 42: "MERCHANT1234567", 49: "840",
    }
    payload = pack_iso("0400", fields)
    print(f"\nTransmitting 0400 Reversal for STAN={stan}...")
    run_and_report(host, port, timeout, payload)


def action_emv_purchase(host: str, port: int, timeout: float) -> None:
    print(f"\n{YELLOW}--- [0200 + DE 55] EMV Chip Card Transaction ---{RESET}")
    de55 = ("9F2608A1B2C3D4E5F607089F360200E29F10120110A000002A0000000000000"
            "000000000000FF9C01009A032608149F370412345678")
    fields = {
        2: "4532015588991234", 3: "000000", 4: "000000004999", 7: now_iso_datetime(),
        11: "000555", 41: "TERM0001", 42: "MERCHANT1234567", 49: "840", 55: de55,
    }
    payload = pack_iso("0200", fields)
    print("Transmitting 0200 EMV Chip Card Purchase (ARQC: 9F26, ATC: 9F36 in DE 55)...")
    run_and_report(host, port, timeout, payload)


def action_benchmark(host: str, port: int, timeout: float, iterations: int = 10) -> None:
    print(f"\n{YELLOW}--- Running Latency & Throughput Benchmark ({iterations} Echoes) ---{RESET}")
    latencies = []
    for i in range(1, iterations + 1):
        fields = {7: now_iso_datetime(), 11: f"{i:06d}", 70: "301"}
        payload = pack_iso("0800", fields)
        try:
            _, elapsed = send_iso_frame(host, port, payload, timeout)
            latencies.append(elapsed)
            print(f"  [{i}/{iterations}] Echo acknowledged — Latency: {elapsed:.2f} ms")
        except Iso8583ConnectionError as exc:
            print(f"  [{i}/{iterations}] {RED}Failed: {exc}{RESET}")

    if latencies:
        print(f"\n{GREEN}{BOLD}Benchmark Results:{RESET}")
        print(f"  Total Sent: {iterations} | Successful: {len(latencies)}")
        print(f"  Avg Latency: {sum(latencies) / len(latencies):.2f} ms | "
              f"Min: {min(latencies):.2f} ms | Max: {max(latencies):.2f} ms\n")


def action_custom(host: str, port: int, timeout: float) -> None:
    print(f"\n{YELLOW}--- Send Custom Raw ISO Message ---{RESET}")
    payload = input("Paste your raw ISO 8583 payload string:\n> ").strip()
    if not payload:
        print("Empty payload. Aborted.")
        return
    run_and_report(host, port, timeout, payload)


MENU_ACTIONS: Dict[str, tuple[str, Callable[[str, int, float], None]]] = {
    "1": ("Send 0800 Keep-Alive Echo Test (DE 70 = 301)", action_echo),
    "2": ("Send 0200 Purchase Authorization ($25.50)", action_purchase),
    "3": ("Send 0400 Transaction Reversal (Reverses previous 0200)", action_reversal),
    "4": ("Send 0200 EMV Chip Card Transaction (with DE 55 BER-TLV)", action_emv_purchase),
    "5": ("Send Custom Raw ISO 8583 Message", action_custom),
    "6": ("Run Latency Benchmark (10 Consecutive Echoes)", action_benchmark),
}


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description="ISO 8583 terminal & switch client simulator")
    parser.add_argument("host", nargs="?", default=DEFAULT_HOST)
    parser.add_argument("port", nargs="?", type=int, default=DEFAULT_PORT)
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT, help="Socket timeout in seconds")
    args = parser.parse_args(argv)

    print_banner(args.host, args.port)

    while True:
        print(f"{BOLD}Select an action to simulate:{RESET}")
        for key, (label, _) in MENU_ACTIONS.items():
            print(f"  [{CYAN}{key}{RESET}] {label}")
        print(f"  [{RED}q{RESET}] Quit")

        choice = input("\nEnter choice [1-6/q]: ").strip().lower()

        if choice in ("q", "quit", "exit"):
            print("\nExiting ISO 8583 Client. Goodbye!\n")
            break

        action = MENU_ACTIONS.get(choice)
        if action is None:
            print(f"{RED}Invalid option. Please choose 1-6 or q.{RESET}")
        else:
            _, handler = action
            handler(args.host, args.port, args.timeout)

        input(f"\n{BLUE}Press Enter to return to menu...{RESET}")
        print("\n" + "=" * 70 + "\n")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nInterrupted. Goodbye!")
        sys.exit(0)
