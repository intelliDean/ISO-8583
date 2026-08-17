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

import socket
import sys
import time
from datetime import datetime

DEFAULT_HOST = "localhost"
DEFAULT_PORT = 8583
DEFAULT_TPDU = "6000000000"

# ANSI Colors for rich terminal display
GREEN = "\033[92m"
RED = "\033[91m"
BLUE = "\033[94m"
CYAN = "\033[96m"
YELLOW = "\033[93m"
BOLD = "\033[1m"
RESET = "\033[0m"


def send_iso_frame(host: str, port: int, payload: str) -> str:
    """
    Connects to the ISO 8583 TCP server, transmits a length-framed packet,
    and returns the unpacked response string.
    """
    start_time = time.perf_counter()
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10.0)

    try:
        sock.connect((host, port))
        payload_bytes = payload.encode("ascii")
        length_header = len(payload_bytes).to_bytes(2, byteorder="big")

        # Send [2-byte length][payload]
        sock.sendall(length_header + payload_bytes)

        # Read 2-byte response length
        raw_len = sock.recv(2)
        if not raw_len or len(raw_len) < 2:
            raise ConnectionError("Server closed connection without returning length header.")

        resp_len = int.from_bytes(raw_len, byteorder="big")
        received_bytes = bytearray()

        while len(received_bytes) < resp_len:
            chunk = sock.recv(resp_len - len(received_bytes))
            if not chunk:
                break
            received_bytes.extend(chunk)

        elapsed_ms = (time.perf_counter() - start_time) * 1000
        response_str = received_bytes.decode("ascii")

        print(f"\n{GREEN}✔ Response received in {elapsed_ms:.2f} ms ({resp_len} bytes){RESET}")
        return response_str

    finally:
        sock.close()


def print_banner():
    print(f"""{CYAN}{BOLD}
╔══════════════════════════════════════════════════════════════════════╗
║              ISO 8583 TERMINAL & SWITCH CLIENT SIMULATOR             ║
║            Connecting to TCP Socket Server on Port 8583              ║
╚══════════════════════════════════════════════════════════════════════╝{RESET}""")


def parse_response_summary(resp: str):
    """Prints a friendly summary of key response fields."""
    if len(resp) < 14:
        print(f"Raw: {resp}")
        return

    # Check for TPDU header
    has_header = resp.startswith(DEFAULT_TPDU)
    offset = 10 if has_header else 0

    mti = resp[offset:offset + 4]
    print(f"{BOLD}MTI:{RESET} {mti}")


def compute_bitmaps(fields: dict) -> tuple[str, str | None]:
    """Computes the primary and optional secondary 16-hex bitmaps for given field IDs."""
    field_ids = [int(f) for f in fields.keys() if int(f) >= 2]
    has_secondary = any(fid > 64 for fid in field_ids)

    primary_bytes = bytearray(8)
    if has_secondary:
        primary_bytes[0] |= 0x80  # Bit 1 set

    for fid in field_ids:
        if 2 <= fid <= 64:
            bit_idx = fid - 1
            primary_bytes[bit_idx // 8] |= (1 << (7 - (bit_idx % 8)))

    primary_hex = primary_bytes.hex().upper()

    secondary_hex = None
    if has_secondary:
        sec_bytes = bytearray(8)
        for fid in field_ids:
            if 65 <= fid <= 128:
                bit_idx = fid - 65
                sec_bytes[bit_idx // 8] |= (1 << (7 - (bit_idx % 8)))
        secondary_hex = sec_bytes.hex().upper()

    return primary_hex, secondary_hex


def pack_iso(mti: str, fields: dict, header: str = DEFAULT_TPDU) -> str:
    """Dynamically packs an ISO 8583 payload from field map with correct variable prefixes."""
    primary_bm, sec_bm = compute_bitmaps(fields)
    packed = header + mti + primary_bm + (sec_bm or "")

    for fid in sorted(int(k) for k in fields.keys()):
        val = str(fields[fid])
        # Format based on standard field types
        if fid == 2:  # LLVAR PAN
            packed += f"{len(val):02d}{val}"
        elif fid == 3:  # 6-digit ProcCode
            packed += f"{val:>06}"
        elif fid == 4:  # 12-digit Amount
            packed += f"{val:>012}"
        elif fid == 7:  # 10-digit Date/Time
            packed += f"{val:>010}"
        elif fid == 11:  # 6-digit STAN
            packed += f"{val:>06}"
        elif fid == 41:  # 8-char TID
            packed += f"{val:<8}"
        elif fid == 42:  # 15-char MID
            packed += f"{val:<15}"
        elif fid == 49:  # 3-char Currency
            packed += f"{val:>03}"
        elif fid == 55:  # LLLVAR DE 55
            packed += f"{len(val):03d}{val}"
        elif fid == 70:  # 3-digit NetMgmt Code
            packed += f"{val:>03}"
        else:
            packed += f"{len(val):02d}{val}"

    return packed


def action_echo(host, port):
    print(f"\n{YELLOW}--- [0800] Network Management Echo Test ---{RESET}")
    now_str = datetime.utcnow().strftime("%m%d%H%M%S")
    fields = {
        7: now_str,
        11: "000001",
        70: "301"
    }
    payload = pack_iso("0800", fields)
    print(f"Sending 0800 Echo Request (DE 70 = 301)...")
    try:
        resp = send_iso_frame(host, port, payload)
        parse_response_summary(resp)
    except Exception as e:
        print(f"{RED}✘ Echo Error: {e}{RESET}")


def action_purchase(host, port):
    print(f"\n{YELLOW}--- [0200] Purchase Authorization Request ---{RESET}")
    pan = input("Enter Card PAN (or press Enter for default 4532015588991234): ").strip()
    if not pan:
        pan = "4532015588991234"

    amount_input = input("Enter Amount in USD (or press Enter for $25.50): ").strip()
    if not amount_input:
        amount_iso = "000000002550"
    else:
        try:
            cents = int(float(amount_input) * 100)
            amount_iso = f"{cents:012d}"
        except ValueError:
            amount_iso = "000000002550"

    stan = input("Enter 6-digit STAN (or press Enter for 000123): ").strip()
    if not stan or len(stan) != 6:
        stan = "000123"

    now_str = datetime.utcnow().strftime("%m%d%H%M%S")
    fields = {
        2: pan,
        3: "000000",
        4: amount_iso,
        7: now_str,
        11: stan,
        41: "TERM0001",
        42: "MERCHANT1234567",
        49: "840"
    }
    payload = pack_iso("0200", fields)

    print(f"\nTransmitting 0200 Purchase to {host}:{port}...")
    try:
        resp = send_iso_frame(host, port, payload)
        parse_response_summary(resp)
    except Exception as e:
        print(f"{RED}✘ Purchase Error: {e}{RESET}")


def action_reversal(host, port):
    print(f"\n{YELLOW}--- [0400] Transaction Reversal Request ---{RESET}")
    stan = input("Enter 6-digit STAN of original transaction to reverse [default: 000123]: ").strip()
    if not stan:
        stan = "000123"

    pan = input("Enter Card PAN of original transaction [default: 4532015588991234]: ").strip()
    if not pan:
        pan = "4532015588991234"

    now_str = datetime.utcnow().strftime("%m%d%H%M%S")
    fields = {
        2: pan,
        3: "000000",
        4: "000000002550",
        7: now_str,
        11: stan,
        41: "TERM0001",
        42: "MERCHANT1234567",
        49: "840"
    }
    payload = pack_iso("0400", fields)

    print(f"\nTransmitting 0400 Reversal for STAN={stan}...")
    try:
        resp = send_iso_frame(host, port, payload)
        parse_response_summary(resp)
    except Exception as e:
        print(f"{RED}✘ Reversal Error: {e}{RESET}")


def action_emv_purchase(host, port):
    print(f"\n{YELLOW}--- [0200 + DE 55] EMV Chip Card Transaction ---{RESET}")
    de55 = "9F2608A1B2C3D4E5F607089F360200E29F10120110A000002A0000000000000000000000FF9C01009A032608149F370412345678"
    now_str = datetime.utcnow().strftime("%m%d%H%M%S")

    fields = {
        2: "4532015588991234",
        3: "000000",
        4: "000000004999",
        7: now_str,
        11: "000555",
        41: "TERM0001",
        42: "MERCHANT1234567",
        49: "840",
        55: de55
    }
    payload = pack_iso("0200", fields)

    print(f"Transmitting 0200 EMV Chip Card Purchase (ARQC: 9F26, ATC: 9F36 in DE 55)...")
    try:
        resp = send_iso_frame(host, port, payload)
        parse_response_summary(resp)
    except Exception as e:
        print(f"{RED}✘ EMV Error: {e}{RESET}")


def action_benchmark(host, port):
    print(f"\n{YELLOW}--- Running Latency & Throughput Benchmark (10 Echoes) ---{RESET}")
    latencies = []
    for i in range(1, 11):
        now_str = datetime.utcnow().strftime("%m%d%H%M%S")
        stan = f"{i:06d}"
        payload = f"6000000000080082200000000000000400000000000000{now_str}{stan}301"

        t0 = time.perf_counter()
        try:
            send_iso_frame(host, port, payload)
            elapsed = (time.perf_counter() - t0) * 1000
            latencies.append(elapsed)
            print(f"  [{i}/10] Echo acknowledged — Latency: {elapsed:.2f} ms")
        except Exception as e:
            print(f"  [{i}/10] {RED}Failed: {e}{RESET}")

    if latencies:
        avg_lat = sum(latencies) / len(latencies)
        min_lat = min(latencies)
        max_lat = max(latencies)
        print(f"\n{GREEN}{BOLD}Benchmark Results:{RESET}")
        print(f"  Total Sent: 10 | Successful: {len(latencies)}")
        print(f"  Avg Latency: {avg_lat:.2f} ms | Min: {min_lat:.2f} ms | Max: {max_lat:.2f} ms\n")


def action_custom(host, port):
    print(f"\n{YELLOW}--- Send Custom Raw ISO Message ---{RESET}")
    payload = input("Paste your raw ISO 8583 payload string:\n> ").strip()
    if not payload:
        print("Empty payload. Aborted.")
        return
    try:
        resp = send_iso_frame(host, port, payload)
        parse_response_summary(resp)
    except Exception as e:
        print(f"{RED}✘ Error: {e}{RESET}")


def main():
    host = DEFAULT_HOST
    port = DEFAULT_PORT

    if len(sys.argv) > 1:
        host = sys.argv[1]
    if len(sys.argv) > 2:
        port = int(sys.argv[2])

    print_banner()
    print(f"Target Engine: {BOLD}{host}:{port}{RESET}\n")

    while True:
        print(f"{BOLD}Select an action to simulate:{RESET}")
        print(f"  [{CYAN}1{RESET}] Send 0800 Keep-Alive Echo Test (DE 70 = 301)")
        print(f"  [{CYAN}2{RESET}] Send 0200 Purchase Authorization ($25.50)")
        print(f"  [{CYAN}3{RESET}] Send 0400 Transaction Reversal (Reverses previous 0200)")
        print(f"  [{CYAN}4{RESET}] Send 0200 EMV Chip Card Transaction (with DE 55 BER-TLV)")
        print(f"  [{CYAN}5{RESET}] Send Custom Raw ISO 8583 Message")
        print(f"  [{CYAN}6{RESET}] Run Latency Benchmark (10 Consecutive Echoes)")
        print(f"  [{RED}q{RESET}] Quit")

        choice = input(f"\nEnter choice [1-6/q]: ").strip()

        if choice == "1":
            action_echo(host, port)
        elif choice == "2":
            action_purchase(host, port)
        elif choice == "3":
            action_reversal(host, port)
        elif choice == "4":
            action_emv_purchase(host, port)
        elif choice == "5":
            action_custom(host, port)
        elif choice == "6":
            action_benchmark(host, port)
        elif choice.lower() in ("q", "quit", "exit"):
            print("\nExiting ISO 8583 Client. Goodbye!\n")
            break
        else:
            print(f"{RED}Invalid option. Please choose 1-6 or q.{RESET}")

        input(f"\n{BLUE}Press Enter to return to menu...{RESET}")
        print("\n" + "=" * 70 + "\n")


if __name__ == "__main__":
    main()

# python3 client/iso_terminal_client.py
