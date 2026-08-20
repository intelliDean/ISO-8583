"""
=============================================================================
Automated E2E Test Suite — ISO 8583 Payment Engine REST API
=============================================================================
Exercises every tab of the engine's HTTP API: message inspector/unpacker,
message builder + TCP simulator, EMV BER-TLV parser, crypto lab (PIN
blocks / MAC), clearing & settlement, and network telemetry.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_TIMEOUT = 10.0


# --------------------------------------------------------------------------
# HTTP client
# --------------------------------------------------------------------------

class ApiError(Exception):
    """Raised for any HTTP, network, or JSON-decoding failure."""


class ApiClient:
    def __init__(self, base_url: str, timeout: float = DEFAULT_TIMEOUT):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def get(self, endpoint: str) -> Dict[str, Any]:
        return self._request(endpoint, data=None)

    def post(self, endpoint: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        return self._request(endpoint, data=payload)

    def _request(self, endpoint: str, data: Optional[Dict[str, Any]]) -> Dict[str, Any]:
        url = f"{self.base_url}{endpoint}"
        body = json.dumps(data).encode("utf-8") if data is not None else None
        req = urllib.request.Request(
            url,
            data=body,
            headers={"Content-Type": "application/json"} if body else {},
            method="POST" if body else "GET",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")[:500]
            raise ApiError(f"{req.method} {endpoint} -> HTTP {exc.code}: {detail}") from exc
        except urllib.error.URLError as exc:
            raise ApiError(f"{req.method} {endpoint} -> connection failed: {exc.reason}") from exc
        except TimeoutError as exc:
            raise ApiError(f"{req.method} {endpoint} -> timed out after {self.timeout}s") from exc

        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ApiError(f"{req.method} {endpoint} -> invalid JSON response: {raw[:200]!r}") from exc


def _field(res: Dict[str, Any], key: str, tab: str) -> Any:
    """Access a response field with a clear error if it's missing."""
    if key not in res:
        raise ApiError(f"[{tab}] response missing expected field {key!r}: {res}")
    return res[key]


# --------------------------------------------------------------------------
# Test result tracking
# --------------------------------------------------------------------------

@dataclass
class TabResult:
    name: str
    passed: bool
    elapsed_ms: float
    error: Optional[str] = None


@dataclass
class Report:
    results: List[TabResult] = field(default_factory=list)

    @property
    def all_passed(self) -> bool:
        return all(r.passed for r in self.results)

    def summary(self) -> str:
        lines = ["\n" + "=" * 80, "TEST SUMMARY", "=" * 80]
        for r in self.results:
            mark = "✓ PASS" if r.passed else "✗ FAIL"
            lines.append(f"  {mark}  {r.name:<45} ({r.elapsed_ms:.0f} ms)")
            if r.error:
                lines.append(f"         -> {r.error}")
        passed = sum(r.passed for r in self.results)
        lines.append("-" * 80)
        lines.append(f"  {passed}/{len(self.results)} tabs passed")
        lines.append("=" * 80)
        return "\n".join(lines)


def run_tab(report: Report, name: str, fn: Callable[[], None]) -> None:
    start = time.perf_counter()
    try:
        fn()
        report.results.append(TabResult(name, True, (time.perf_counter() - start) * 1000))
    except (ApiError, AssertionError) as exc:
        report.results.append(TabResult(name, False, (time.perf_counter() - start) * 1000, str(exc)))


# --------------------------------------------------------------------------
# Tab tests
# --------------------------------------------------------------------------

def tab1_message_inspector(client: ApiClient) -> None:
    print("\n[TAB 1: MESSAGE INSPECTOR] Testing Presets & Payload Disassembly...")
    presets = {
        "0200 Financial": "600000000002007020000000C0800016453201558899123400000000000000002550000123TERM0001MERCHANT1234567840",
        "0800 Echo": "60000000000800822000000000000004000000000000000820180000000001301",
        "0400 Reversal": "600000000004007020000000C0800016453201558899123400000000000000002550000123TERM0001MERCHANT1234567840",
        "0200 + EMV": "600000000002007020000000C08200164532015588991234000000000000002550000123TERM0001MERCHANT12345678400329F2608A1B2C3D4E5F6079F9F3602001E",
    }
    for name, payload in presets.items():
        res = client.post("/api/iso/unpack", {"payload": payload, "hasHeader": True, "specId": "iso8583-1987"})
        mti = _field(res, "mti", "Tab1")
        fields = _field(res, "fields", "Tab1")
        print(f"  ✓ Preset '{name}': MTI={mti} ({res.get('mtiDescription')}) — {len(fields)} Fields Decoded")
        assert mti is not None, f"Failed on {name}"


def tab2_message_builder(client: ApiClient) -> str:
    print("\n[TAB 2: MESSAGE BUILDER] Testing Message Packaging & Live TCP Socket Simulation...")
    pack_res = client.post("/api/iso/pack", {
        "header": "6000000000",
        "mti": "0200",
        "fields": {
            "2": "4532015588991234",
            "3": "000000",
            "4": "000000005000",
            "11": "000999",
            "41": "TERM0001",
            "42": "MERCHANT1234567",
            "49": "840",
        },
        "specId": "iso8583-1987",
    })
    raw_payload = _field(pack_res, "rawPayload", "Tab2")
    print(f"  ✓ Packed ISO Payload: {raw_payload[:50]}... (Len: {pack_res.get('length')})")

    sim_res = client.post("/api/iso/simulate", {"rawPayload": raw_payload})
    rc = _field(sim_res, "responseCode", "Tab2")
    print(f"  ✓ TCP Simulator Response: MTI={sim_res.get('responseMti')} RC={rc} "
          f"({sim_res.get('responseCodeDescription')}) in {sim_res.get('roundtripMs')}ms")
    assert rc == "00", f"Simulation should return Approved (00), got {rc}"
    return raw_payload


def tab4_emv_parser(client: ApiClient) -> None:
    print("\n[TAB 4: EMV BER-TLV EXPLORER] Decoding DE 55 Chip Stream & Fraud Signals...")
    emv_sample = "9F2608A1B2C3D4E5F6079F9F3602001E9F10120110A000002A0000000000000000000000FF9C010082020100"
    res = client.post("/api/iso/emv/parse", {"de55Hex": emv_sample})
    print(f"  ✓ Parsed {res.get('tagCount')} TLV Tags:")
    print(f"    - Tag 9F26 [ARQC]: {res.get('arqcValue')} (present: {res.get('hasArqc')})")
    print(f"    - Tag 9F36 [ATC]: {res.get('atcValue')} (Dec: {res.get('atcDecimal')}, present: {res.get('hasAtc')})")
    assert _field(res, "hasArqc", "Tab4"), "Should detect ARQC (9F26)"
    assert _field(res, "hasAtc", "Tab4"), "Should detect ATC (9F36)"


def tab5_crypto_lab(client: ApiClient, raw_payload: str) -> None:
    print("\n[TAB 5: CRYPTOGRAPHY LAB] Testing PIN Block Formats, Retail MAC & Translation...")

    pin_res = client.post("/api/iso/crypto/pin/encode", {
        "pin": "1234", "pan": "4532015588991234", "format": "FORMAT_0",
    })
    encrypted_block = _field(pin_res, "encryptedBlockHex", "Tab5")
    print(f"  ✓ PIN Format 0: Clear={pin_res.get('clearBlockHex')} "
          f"Encrypted={encrypted_block} (Key: {pin_res.get('keyUsed')})")

    trans_res = client.post("/api/iso/crypto/pin/translate", {
        "encryptedBlockHex": encrypted_block,
        "srcPan": "4532015588991234",
        "srcFormat": "FORMAT_0",
        "dstFormat": "FORMAT_1",
    })
    print(f"  ✓ PIN Translation: {trans_res.get('srcFormat')} -> {trans_res.get('dstFormat')} "
          f"=> Translated: {trans_res.get('translatedBlockHex')} (Success: {trans_res.get('success')})")

    mac_res = client.post("/api/iso/crypto/mac/generate", {"rawPayload": raw_payload})
    mac_hex = _field(mac_res, "macHex", "Tab5")
    print(f"  ✓ ISO 9797-1 Retail MAC (DE 64): {mac_hex} ({mac_res.get('algorithm')})")

    mac_ver = client.post("/api/iso/crypto/mac/verify", {"rawPayload": raw_payload, "expectedMac": mac_hex})
    print(f"  ✓ Retail MAC Verification: Valid={mac_ver.get('valid')} ({mac_ver.get('message')})")
    assert _field(mac_ver, "valid", "Tab5"), "MAC should verify successfully"


def tab6_clearing_settlement(client: ApiClient) -> None:
    print("\n[TAB 6: CLEARING & SETTLEMENT] Generating End-of-Day 1240 Presentment Batch...")
    batch_res = client.post("/api/iso/clearing/batch/generate", {"networkId": "MASTERCARD-IPM"})
    print(f"  ✓ Batch Generated: ID={batch_res.get('batchId')} Date={batch_res.get('settlementDate')} "
          f"Network={batch_res.get('networkId')}")
    print(f"    - Total Records: {batch_res.get('totalTransactions')} "
          f"(Presentments: {batch_res.get('presentmentCount')}, Chargebacks: {batch_res.get('chargebackCount')})")
    for label, key in (
        ("Gross Amount", "totalGrossAmountIso"),
        ("Interchange Fee", "totalInterchangeFeeIso"),
        ("Net Settlement", "netSettlementAmountIso"),
    ):
        value = _field(batch_res, key, "Tab6")
        print(f"    - {label}: ${int(value) / 100:.2f}")

    cb_res = client.post("/api/iso/clearing/chargeback", {
        "stan": "000999",
        "maskedPan": "453201******1234",
        "amountIso": "000000005000",
        "disputeReasonCode": "4837",
    })
    print(f"  ✓ Filed 1440 Chargeback: ID={cb_res.get('recordId')} STAN={cb_res.get('stan')} "
          f"Reason={cb_res.get('disputeReasonCode')}")


def tab7_network_telemetry(client: ApiClient) -> None:
    print("\n[TAB 7: NETWORK TELEMETRY & RESILIENCY] Testing 0800/0810 Echo & Outbox Stream...")
    echo_res = client.post("/api/iso/echo/trigger", {})
    success = _field(echo_res, "success", "Tab7")
    print(f"  ✓ Echo Heartbeat (0800 -> 0810): Success={success} "
          f"RTT={echo_res.get('roundtripMs')}ms RC={echo_res.get('responseCode')}")
    assert success, "Echo should be successful"

    status = client.get("/api/iso/resiliency/status")
    print(f"  ✓ Persistence Engine: {status.get('persistenceEngine')}")
    print(f"  ✓ Distributed Lock: {status.get('lockEngine')}")
    print(f"  ✓ Outbox Status: {status.get('outboxStatus')}")
    print(f"  ✓ Total Dispatched Events: {status.get('totalEventsDispatched')}")
    print(f"  ✓ Pending Outbox Events: {status.get('pendingOutboxEvents')}")


# --------------------------------------------------------------------------
# Runner
# --------------------------------------------------------------------------

def build_tabs(client: ApiClient) -> "list[tuple[str, Callable[[], None]]]":
    # Tab 2's output (raw_payload) feeds Tab 5, so it's captured via closure state.
    state: Dict[str, str] = {}

    def _tab2():
        state["raw_payload"] = tab2_message_builder(client)

    def _tab5():
        if "raw_payload" not in state:
            raise ApiError("[Tab5] requires Tab2 to have run first (needs a packed payload)")
        tab5_crypto_lab(client, state["raw_payload"])

    return [
        ("Tab 1: Message Inspector", lambda: tab1_message_inspector(client)),
        ("Tab 2: Message Builder + TCP Sim", _tab2),
        ("Tab 4: EMV BER-TLV Parser", lambda: tab4_emv_parser(client)),
        ("Tab 5: Crypto Lab", _tab5),
        ("Tab 6: Clearing & Settlement", lambda: tab6_clearing_settlement(client)),
        ("Tab 7: Network Telemetry", lambda: tab7_network_telemetry(client)),
    ]


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="E2E test suite for the ISO 8583 payment engine API")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT)
    parser.add_argument("--tab", action="append", help="Run only tabs whose name contains this substring "
                                                         "(repeatable). Default: run all.")
    args = parser.parse_args(argv)

    print("=" * 80)
    print("🚀 AUTOMATED E2E BROWSER & PROTOCOL TEST SUITE FOR ISO 8583 ENGINE")
    print(f"   Target: {args.base_url}")
    print("=" * 80)

    client = ApiClient(args.base_url, timeout=args.timeout)
    tabs = build_tabs(client)
    if args.tab:
        wanted = [t.lower() for t in args.tab]
        tabs = [(name, fn) for name, fn in tabs if any(w in name.lower() for w in wanted)]
        if not tabs:
            print(f"No tabs matched filter(s): {args.tab}")
            return 1

    report = Report()
    for name, fn in tabs:
        run_tab(report, name, fn)

    print(report.summary())

    if report.all_passed:
        print("\n🎉 ALL SELECTED TABS VERIFIED (100% OPERATIONAL & HEALTHY)")
        return 0
    else:
        print("\n⚠️  ONE OR MORE TABS FAILED — see summary above")
        return 1


if __name__ == "__main__":
    sys.exit(main())