#!/usr/bin/env python3

"""
=============================================================================
ISO 8583 Terminal & POS Host Client Simulator
=============================================================================
Developer Note:
This interactive client communicates with the ISO 8583 Payment Engine over
raw TCP or TLS/mTLS Sockets (port 8583) using the standard 2-byte Big-Endian length header.

Features:
  1. Purchase Transaction (0200 -> 0210)
  2. Transaction Reversal (0400 -> 0410)
  3. Network Management Echo Heartbeat (0800 -> 0810)
  4. EMV Chip Card Transaction with DE 55 BER-TLV (0200 -> 0210)
  5. Custom Raw ISO Message Transmission
  6. High-Throughput Latency Benchmark (10 consecutive echoes)
  7. Non-Interactive CLI Automation Mode (--action / --payload)
  8. SSL / TLS 1.3 Encryption & Mutual TLS (mTLS) Support (--tls, --cert, --key)
=============================================================================
"""

from __future__ import annotations

import argparse
import socket
import ssl
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Callable, Dict, List, Optional, Tuple

DEFAULT_HOST = "localhost"
DEFAULT_PORT = 8583
DEFAULT_TPDU = "6000000000"
DEFAULT_TIMEOUT = 10.0

# ANSI colors & formatting
GREEN = "\033[92m"
RED = "\033[91m"
BLUE = "\033[94m"
CYAN = "\033[96m"
YELLOW = "\033[93m"
MAGENTA = "\033[95m"
GRAY = "\033[90m"
BOLD = "\033[1m"
RESET = "\033[0m"

MTI_DESCRIPTIONS = {
    "0200": "Financial Transaction Request (Purchase Authorization)",
    "0210": "Financial Transaction Response (Authorization Result)",
    "0400": "Reversal Request (Transaction Cancellation)",
    "0410": "Reversal Response (Cancellation Acknowledged)",
    "0800": "Network Management Request (Echo / Keep-Alive)",
    "0810": "Network Management Response (Echo / Keep-Alive Acknowledged)",
}

RESPONSE_CODES: Dict[str, str] = {
    "00": "Approved / Completed Successfully",
    "01": "Refer to Card Issuer",
    "04": "Pick Up Card",
    "05": "Do Not Honor",
    "12": "Invalid Transaction",
    "13": "Invalid Amount",
    "14": "Invalid Card Number",
    "30": "Format Error",
    "41": "Lost Card",
    "43": "Stolen Card",
    "51": "Insufficient Funds",
    "54": "Expired Card",
    "55": "Incorrect PIN",
    "57": "Transaction Not Permitted to Cardholder",
    "58": "Transaction Not Permitted to Terminal",
    "61": "Exceeds Withdrawal Amount Limit",
    "91": "System Error / Issuer Unavailable",
    "96": "System Malfunction",
}

# Known response field length rules: (Name, Length/Type)
FIELD_UNPACK_DEFS: Dict[int, Tuple[str, str | int]] = {
    2:  ("Primary Account Number (PAN)", "LLVAR"),
    3:  ("Processing Code", 6),
    4:  ("Amount, Transaction", 12),
    7:  ("Transmission Date & Time", 10),
    11: ("Systems Trace Audit Number (STAN)", 6),
    12: ("Time, Local Transaction", 6),
    13: ("Date, Local Transaction", 4),
    14: ("Date, Expiration", 4),
    37: ("Retrieval Reference Number (RRN)", 12),
    38: ("Authorization ID Response", 6),
    39: ("Response Code", 2),
    41: ("Card Acceptor Terminal ID (CATID)", 8),
    42: ("Card Acceptor ID (CAID)", 15),
    49: ("Currency Code, Transaction", 3),
    55: ("ICC System Related Data (EMV)", "LLLVAR"),
    70: ("Network Management Information Code", 3),
}


# --------------------------------------------------------------------------
# Connection Configuration Dataclass
# --------------------------------------------------------------------------

@dataclass
class ConnectionConfig:
    host: str = DEFAULT_HOST
    port: int = DEFAULT_PORT
    timeout: float = DEFAULT_TIMEOUT
    use_tls: bool = False
    insecure: bool = False
    cert_file: Optional[str] = None
    key_file: Optional[str] = None
    ca_cert: Optional[str] = None


# --------------------------------------------------------------------------
# Exceptions & Formatter Closures
# --------------------------------------------------------------------------

class FieldFormatError(ValueError):
    """Raised when a field value violates its ISO 8583 specification."""


class Iso8583ConnectionError(Exception):
    """Raised when socket communication with the ISO host fails."""


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


FIELD_FORMATTERS: Dict[int, Callable[[str], str]] = {
    2:  _fmt_llvar,
    3:  _fmt_fixed_numeric(6),
    4:  _fmt_fixed_numeric(12),
    7:  _fmt_fixed_numeric(10),
    11: _fmt_fixed_numeric(6),
    41: _fmt_fixed_alpha(8),
    42: _fmt_fixed_alpha(15),
    49: _fmt_fixed_numeric(3),
    55: _fmt_lllvar,
    70: _fmt_fixed_numeric(3),
}


# --------------------------------------------------------------------------
# Packing & Bitmap Logic
# --------------------------------------------------------------------------

def compute_bitmaps(field_ids) -> tuple[str, Optional[str]]:
    """Computes primary (and optional secondary) 16-hex-character bitmaps."""
    field_ids = sorted(int(f) for f in field_ids if int(f) >= 2)
    has_secondary = any(fid > 64 for fid in field_ids)

    primary = bytearray(8)
    if has_secondary:
        primary[0] |= 0x80  # Bit 1 = Secondary Bitmap present

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
    """Packs an ISO 8583 message string from an MTI and field-id mapping."""
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


# --------------------------------------------------------------------------
# True Bitmap-Aware Response Unpacker
# --------------------------------------------------------------------------

@dataclass
class ParsedResponse:
    raw: str
    header: Optional[str]
    mti: str
    mti_description: str
    primary_bitmap: str
    secondary_bitmap: Optional[str]
    fields: Dict[int, str] = field(default_factory=dict)
    response_code: Optional[str] = None
    response_description: Optional[str] = None
    auth_code: Optional[str] = None
    rrn: Optional[str] = None
    stan: Optional[str] = None
    approved: bool = False


def parse_response(resp: str) -> ParsedResponse:
    """Performs full bitmap disassembly and structured field extraction."""
    has_header = resp.startswith(DEFAULT_TPDU)
    offset = len(DEFAULT_TPDU) if has_header else 0

    if len(resp) < offset + 20:
        return ParsedResponse(
            raw=resp,
            header=resp[:offset] if has_header else None,
            mti="UNKNOWN",
            mti_description="Malformed / Truncated Response",
            primary_bitmap="",
            secondary_bitmap=None
        )

    mti = resp[offset:offset + 4]
    offset += 4
    mti_desc = MTI_DESCRIPTIONS.get(mti, "ISO 8583 Response")

    p_bm = resp[offset:offset + 16]
    offset += 16

    active_fields: List[int] = []
    try:
        p_bytes = bytes.fromhex(p_bm)
        for i in range(64):
            byte_idx = i // 8
            bit_idx = 7 - (i % 8)
            if (p_bytes[byte_idx] >> bit_idx) & 1:
                active_fields.append(i + 1)
    except ValueError:
        pass

    s_bm = None
    if 1 in active_fields:
        active_fields.remove(1)
        if len(resp) >= offset + 16:
            s_bm = resp[offset:offset + 16]
            offset += 16
            try:
                s_bytes = bytes.fromhex(s_bm)
                for i in range(64):
                    byte_idx = i // 8
                    bit_idx = 7 - (i % 8)
                    if (s_bytes[byte_idx] >> bit_idx) & 1:
                        active_fields.append(65 + i)
            except ValueError:
                pass

    extracted_fields: Dict[int, str] = {}
    for fid in sorted(active_fields):
        if offset >= len(resp):
            break
        spec = FIELD_UNPACK_DEFS.get(fid)
        try:
            if spec is None:
                flen = int(resp[offset:offset + 2])
                offset += 2
                val = resp[offset:offset + flen]
                offset += flen
            else:
                _, ftype = spec
                if ftype == "LLVAR":
                    flen = int(resp[offset:offset + 2])
                    offset += 2
                    val = resp[offset:offset + flen]
                    offset += flen
                elif ftype == "LLLVAR":
                    flen = int(resp[offset:offset + 3])
                    offset += 3
                    val = resp[offset:offset + flen]
                    offset += flen
                else:
                    width = int(ftype)
                    val = resp[offset:offset + width]
                    offset += width
            extracted_fields[fid] = val
        except (ValueError, IndexError):
            break

    rc = extracted_fields.get(39)
    rc_desc = RESPONSE_CODES.get(rc, f"Code {rc}") if rc else None
    approved = (rc == "00")

    return ParsedResponse(
        raw=resp,
        header=resp[:10] if has_header else None,
        mti=mti,
        mti_description=mti_desc,
        primary_bitmap=p_bm,
        secondary_bitmap=s_bm,
        fields=extracted_fields,
        response_code=rc,
        response_description=rc_desc,
        auth_code=extracted_fields.get(38),
        rrn=extracted_fields.get(37),
        stan=extracted_fields.get(11),
        approved=approved
    )


def colorize_payload(payload: str) -> str:
    """Returns a terminal colorized representation of the ISO message segments."""
    has_header = payload.startswith(DEFAULT_TPDU)
    off = 10 if has_header else 0
    mti = payload[off:off + 4]

    p_bm = payload[off + 4:off + 20]
    has_sec = False
    try:
        has_sec = bool(int(p_bm[0], 16) & 0x8)
    except (ValueError, IndexError):
        pass

    sec_bm = ""
    rest_idx = off + 20
    if has_sec and len(payload) >= off + 36:
        sec_bm = payload[off + 20:off + 36]
        rest_idx = off + 36

    data_elements = payload[rest_idx:]

    hdr_str = f"{GRAY}{payload[:off]}{RESET}" if has_header else ""
    mti_str = f"{CYAN}{BOLD}{mti}{RESET}"
    bm_str = f"{YELLOW}{p_bm}{sec_bm}{RESET}"
    data_str = f"{GREEN}{data_elements}{RESET}"
    return f"{hdr_str}{mti_str}{bm_str}{data_str}"


def now_iso_datetime() -> str:
    return datetime.now(timezone.utc).strftime("%m%d%H%M%S")


# --------------------------------------------------------------------------
# Network I/O with SSL/TLS Support
# --------------------------------------------------------------------------

def send_iso_frame(cfg: ConnectionConfig, payload: str) -> tuple[str, float]:
    """Transmits a length-prefixed ISO 8583 TCP/TLS frame and returns (response_str, elapsed_ms)."""
    payload_bytes = payload.encode("ascii")
    length_header = len(payload_bytes).to_bytes(2, byteorder="big")

    start = time.perf_counter()
    try:
        raw_sock = socket.create_connection((cfg.host, cfg.port), timeout=cfg.timeout)
        if cfg.use_tls:
            if cfg.insecure:
                context = ssl._create_unverified_context()
            else:
                context = ssl.create_default_context(ssl.Purpose.SERVER_AUTH, cafile=cfg.ca_cert)
            if cfg.cert_file and cfg.key_file:
                context.load_cert_chain(certfile=cfg.cert_file, keyfile=cfg.key_file)
            sock = context.wrap_socket(raw_sock, server_hostname=cfg.host)
        else:
            sock = raw_sock

        with sock:
            sock.sendall(length_header + payload_bytes)

            raw_len = _recv_exact(sock, 2)
            resp_len = int.from_bytes(raw_len, byteorder="big")
            received = _recv_exact(sock, resp_len)
    except socket.timeout as exc:
        raise Iso8583ConnectionError(f"Timed out after {cfg.timeout}s waiting on host {cfg.host}:{cfg.port}") from exc
    except ConnectionRefusedError as exc:
        raise Iso8583ConnectionError(f"Connection refused by host {cfg.host}:{cfg.port} — is the ISO engine running?") from exc
    except ssl.SSLError as exc:
        raise Iso8583ConnectionError(f"SSL/TLS handshake error with {cfg.host}:{cfg.port}: {exc}") from exc
    except OSError as exc:
        raise Iso8583ConnectionError(f"Socket error communicating with {cfg.host}:{cfg.port}: {exc}") from exc

    elapsed_ms = (time.perf_counter() - start) * 1000
    return received.decode("ascii"), elapsed_ms


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise Iso8583ConnectionError("Connection closed by peer before expected frame was fully received.")
        buf.extend(chunk)
    return bytes(buf)


# --------------------------------------------------------------------------
# CLI Presentation & Actions
# --------------------------------------------------------------------------

def print_banner(cfg: ConnectionConfig) -> None:
    mode_str = f"{YELLOW}TLS 1.3 Encrypted{RESET}" if cfg.use_tls else f"{GRAY}Plain TCP{RESET}"
    print(f"""{CYAN}{BOLD}
╔══════════════════════════════════════════════════════════════════════╗
║              ISO 8583 TERMINAL & SWITCH CLIENT SIMULATOR             ║
║            Connecting to Socket Server on Port {cfg.port:<5}                 ║
╚══════════════════════════════════════════════════════════════════════╝{RESET}""")
    print(f"Target Host: {BOLD}{cfg.host}:{cfg.port}{RESET} | Security Mode: {mode_str}\n")


def display_response(resp: str, elapsed_ms: float, resp_len: int) -> bool:
    print(f"\n{GREEN}✔ Response received in {elapsed_ms:.2f} ms ({resp_len} bytes){RESET}")
    parsed = parse_response(resp)

    print(f"{CYAN}{BOLD}Message Details:{RESET}")
    if parsed.header:
        print(f"  {BOLD}TPDU Header:{RESET}    {parsed.header}")
    print(f"  {BOLD}MTI:{RESET}            {parsed.mti} ({parsed.mti_description})")
    print(f"  {BOLD}Bitmaps:{RESET}        Primary={parsed.primary_bitmap}" + (f" Secondary={parsed.secondary_bitmap}" if parsed.secondary_bitmap else ""))
    print(f"  {BOLD}Formatted Raw:{RESET}  {colorize_payload(parsed.raw)}")

    if parsed.fields:
        print(f"\n{CYAN}{BOLD}Decoded Data Elements:{RESET}")
        for fid, val in sorted(parsed.fields.items()):
            name = FIELD_UNPACK_DEFS.get(fid, (f"Field {fid}", ""))[0]
            print(f"  {BOLD}DE {fid:>3}{RESET} ({name:<32}): {MAGENTA}{val}{RESET}")

    print()
    if parsed.approved:
        print(f"  {BOLD}Transaction Status:{RESET} {GREEN}{BOLD}✔ APPROVED / SUCCESSFUL (RC {parsed.response_code} — {parsed.response_description}){RESET}")
        if parsed.auth_code:
            print(f"  {BOLD}Authorization Code:{RESET} {GREEN}{parsed.auth_code}{RESET}")
    elif parsed.response_code:
        print(f"  {BOLD}Transaction Status:{RESET} {RED}{BOLD}✘ DECLINED (RC {parsed.response_code} — {parsed.response_description}){RESET}")
    else:
        print(f"  {BOLD}Transaction Status:{RESET} {YELLOW}Awaiting verification{RESET}")

    return parsed.approved


def run_and_report(cfg: ConnectionConfig, payload: str) -> bool:
    try:
        resp, elapsed_ms = send_iso_frame(cfg, payload)
        return display_response(resp, elapsed_ms, len(resp))
    except (Iso8583ConnectionError, FieldFormatError) as exc:
        print(f"{RED}✘ {exc}{RESET}")
        return False


def action_echo(cfg: ConnectionConfig, stan: str = "000001") -> bool:
    print(f"\n{YELLOW}--- [0800] Network Management Echo Test ---{RESET}")
    fields = {7: now_iso_datetime(), 11: stan, 70: "301"}
    payload = pack_iso("0800", fields)
    print(f"Sending 0800 Echo Request (STAN={stan}, DE 70=301)...")
    return run_and_report(cfg, payload)


def _prompt(msg: str, default: str) -> str:
    val = input(msg).strip()
    return val if val else default


def action_purchase(cfg: ConnectionConfig, pan: Optional[str] = None, amount_iso: Optional[str] = None, stan: Optional[str] = None) -> bool:
    print(f"\n{YELLOW}--- [0200] Purchase Authorization Request ---{RESET}")
    if pan is None:
        pan = _prompt("Enter Card PAN [default: 4532015588991234]: ", "4532015588991234")

    if amount_iso is None:
        amount_input = input("Enter Amount in USD [default: $25.50]: ").strip()
        try:
            amount_iso = f"{int(float(amount_input) * 100):012d}" if amount_input else "000000002550"
        except ValueError:
            print(f"{YELLOW}Could not parse amount, using $25.50{RESET}")
            amount_iso = "000000002550"

    if stan is None:
        stan = input("Enter 6-digit STAN [default: 000123]: ").strip()
        if not stan or len(stan) != 6 or not stan.isdigit():
            stan = "000123"

    fields = {
        2: pan, 3: "000000", 4: amount_iso, 7: now_iso_datetime(), 11: stan,
        41: "TERM0001", 42: "MERCHANT1234567", 49: "840",
    }
    payload = pack_iso("0200", fields)
    print(f"\nTransmitting 0200 Purchase to {cfg.host}:{cfg.port}...")
    return run_and_report(cfg, payload)


def action_reversal(cfg: ConnectionConfig, stan: Optional[str] = None, pan: Optional[str] = None) -> bool:
    print(f"\n{YELLOW}--- [0400] Transaction Reversal Request ---{RESET}")
    if stan is None:
        stan = _prompt("Enter 6-digit STAN of original transaction to reverse [default: 000123]: ", "000123")
    if pan is None:
        pan = _prompt("Enter Card PAN of original transaction [default: 4532015588991234]: ", "4532015588991234")

    fields = {
        2: pan, 3: "000000", 4: "000000002550", 7: now_iso_datetime(), 11: stan,
        41: "TERM0001", 42: "MERCHANT1234567", 49: "840",
    }
    payload = pack_iso("0400", fields)
    print(f"\nTransmitting 0400 Reversal for STAN={stan}...")
    return run_and_report(cfg, payload)


def action_emv_purchase(cfg: ConnectionConfig, pan: str = "4532015588991234", amount_iso: str = "000000004999", stan: str = "000555") -> bool:
    print(f"\n{YELLOW}--- [0200 + DE 55] EMV Chip Card Transaction ---{RESET}")
    de55 = "9F2608A1B2C3D4E5F6079F9F3602001E9F10120110A000002A0000000000000000000000FF9C010082020100"
    fields = {
        2: pan, 3: "000000", 4: amount_iso, 7: now_iso_datetime(),
        11: stan, 41: "TERM0001", 42: "MERCHANT1234567", 49: "840", 55: de55,
    }
    payload = pack_iso("0200", fields)
    print("Transmitting 0200 EMV Chip Card Purchase (ARQC: 9F26, ATC: 9F36 in DE 55)...")
    return run_and_report(cfg, payload)


def action_benchmark(cfg: ConnectionConfig, iterations: int = 10) -> bool:
    print(f"\n{YELLOW}--- Running Latency & Throughput Benchmark ({iterations} Echoes) ---{RESET}")
    latencies = []
    for i in range(1, iterations + 1):
        fields = {7: now_iso_datetime(), 11: f"{i:06d}", 70: "301"}
        payload = pack_iso("0800", fields)
        try:
            _, elapsed = send_iso_frame(cfg, payload)
            latencies.append(elapsed)
            print(f"  [{i}/{iterations}] Echo acknowledged — Latency: {elapsed:.2f} ms")
        except Iso8583ConnectionError as exc:
            print(f"  [{i}/{iterations}] {RED}Failed: {exc}{RESET}")

    if latencies:
        print(f"\n{GREEN}{BOLD}Benchmark Results:{RESET}")
        print(f"  Total Dispatched: {iterations} | Successfully Acknowledged: {len(latencies)}")
        print(f"  Avg Latency: {sum(latencies) / len(latencies):.2f} ms | "
              f"Min: {min(latencies):.2f} ms | Max: {max(latencies):.2f} ms\n")
        return len(latencies) == iterations
    return False


def action_custom(cfg: ConnectionConfig, payload: Optional[str] = None) -> bool:
    print(f"\n{YELLOW}--- Send Custom Raw ISO Message ---{RESET}")
    if not payload:
        payload = input("Paste your raw ISO 8583 payload string:\n> ").strip()
    if not payload:
        print("Empty payload. Aborted.")
        return False
    return run_and_report(cfg, payload)


MENU_ACTIONS: Dict[str, tuple[str, Callable[[ConnectionConfig], bool]]] = {
    "1": ("Send 0800 Keep-Alive Echo Test (DE 70 = 301)", lambda c: action_echo(c)),
    "2": ("Send 0200 Purchase Authorization ($25.50)", lambda c: action_purchase(c)),
    "3": ("Send 0400 Transaction Reversal (Reverses previous 0200)", lambda c: action_reversal(c)),
    "4": ("Send 0200 EMV Chip Card Transaction (with DE 55 BER-TLV)", lambda c: action_emv_purchase(c)),
    "5": ("Send Custom Raw ISO 8583 Message", lambda c: action_custom(c)),
    "6": ("Run Latency Benchmark (10 Consecutive Echoes)", lambda c: action_benchmark(c)),
}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="ISO 8583 Terminal & Switch Client Simulator")
    parser.add_argument("host", nargs="?", default=DEFAULT_HOST, help="Host/IP of ISO 8583 engine")
    parser.add_argument("port", nargs="?", type=int, default=DEFAULT_PORT, help="Port of ISO 8583 engine")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT, help="Socket timeout in seconds")
    parser.add_argument("--tls", action="store_true", help="Enable SSL/TLS encryption for socket communication")
    parser.add_argument("--insecure", action="store_true", help="Allow unverified / self-signed server certificates")
    parser.add_argument("--ca-cert", help="Path to CA certificate PEM to verify server")
    parser.add_argument("--cert", help="Path to client certificate PEM for Mutual TLS (mTLS)")
    parser.add_argument("--key", help="Path to client private key PEM for Mutual TLS (mTLS)")
    parser.add_argument("--action", choices=["echo", "purchase", "reversal", "emv", "benchmark"], help="Run a specific action non-interactively and exit")
    parser.add_argument("--payload", help="Send a custom raw ISO 8583 payload non-interactively")
    parser.add_argument("--pan", help="Override Card PAN for purchase/reversal")
    parser.add_argument("--amount", help="Override Amount in minor units (e.g. 000000002550)")
    parser.add_argument("--stan", help="Override 6-digit STAN")
    parser.add_argument("--iterations", type=int, default=10, help="Number of benchmark iterations")

    args = parser.parse_args(argv)

    cfg = ConnectionConfig(
        host=args.host,
        port=args.port,
        timeout=args.timeout,
        use_tls=args.tls,
        insecure=args.insecure,
        cert_file=args.cert,
        key_file=args.key,
        ca_cert=args.ca_cert
    )

    # Non-interactive CLI mode
    if args.payload:
        success = action_custom(cfg, args.payload)
        return 0 if success else 1

    if args.action:
        if args.action == "echo":
            success = action_echo(cfg, args.stan or "000001")
        elif args.action == "purchase":
            success = action_purchase(cfg, args.pan or "4532015588991234", args.amount or "000000002550", args.stan or "000123")
        elif args.action == "reversal":
            success = action_reversal(cfg, args.stan or "000123", args.pan or "4532015588991234")
        elif args.action == "emv":
            success = action_emv_purchase(cfg, args.pan or "4532015588991234", args.amount or "000000004999", args.stan or "000555")
        elif args.action == "benchmark":
            success = action_benchmark(cfg, args.iterations)
        else:
            success = False
        return 0 if success else 1

    # Interactive Menu Mode
    print_banner(cfg)

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
            handler(cfg)

        input(f"\n{BLUE}Press Enter to return to menu...{RESET}")
        print("\n" + "=" * 70 + "\n")

    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\nInterrupted. Goodbye!")
        sys.exit(0)