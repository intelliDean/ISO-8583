package com.dean.iso8583.web;

import com.dean.iso8583.core.dto.IsoFieldDef;
import com.dean.iso8583.core.dto.IsoSpecDefinition;
import com.dean.iso8583.core.echo.dto.ChannelStatusReport;
import com.dean.iso8583.core.echo.dto.EchoResult;
import com.dean.iso8583.web.data.dto.*;
import com.dean.iso8583.web.service.ISO8583Service;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Developer Note:
 * <p>REST API Controller providing ISO 8583 message parsing, packing, dialect specification catalog,
 * host simulation, and EMV DE 55 BER-TLV decoding endpoints.</p>
 *<ul>
 * EMV Endpoint:
 *
 *  <li>POST /api/iso/emv/parse — decodes raw DE 55 hex into structured TLV tags.</li>
 *  <li>Surfaces ARQC (9F26) and ATC (9F36) as top-level response fields for</li>
 *  rapid fraud-signal consumption by downstream issuer systems.
 *  </ul>
 */
@RestController
@RequestMapping("/api/iso")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class Iso8583Controller {

    private final ISO8583Service iso8583Service;

    @GetMapping("/spec")
    public ResponseEntity<Map<Integer, IsoFieldDef>> getCatalog() {
        return ResponseEntity.ok(iso8583Service.getCatalog());
    }

    @GetMapping("/specs")
    public ResponseEntity<Map<String, IsoSpecDefinition>> getSpecs() {
        return ResponseEntity.ok(iso8583Service.getSpecs());
    }

    @PostMapping("/unpack")
    public ResponseEntity<UnpackResult> unpackMessage(@RequestBody UnpackRequest request) {
        return ResponseEntity.ok(iso8583Service.unpackMessage(request));
    }

    @PostMapping("/pack")
    public ResponseEntity<PackResult> packMessage(@RequestBody PackRequest request) {
        return ResponseEntity.ok(iso8583Service.packMessage(request));
    }

    @PostMapping("/simulate")
    public ResponseEntity<SimulateResult> simulateTransaction(@RequestBody SimulateRequest request) {
        return ResponseEntity.ok(iso8583Service.simulateTransaction(request));
    }

    /**
     * POST /api/iso/emv/parse
     *
     * <p>Decodes a hex-encoded DE 55 (ICC System Related Data) payload into
     * its constituent BER-TLV tag-length-value triplets.</p>
     *
     * <p>Developer Note: This endpoint is used by issuer host systems to extract
     * and validate EMV cryptographic data ({@code ARQC}) and the Application
     * Transaction Counter ({@code ATC}) prior to authorisation decision.</p>
     *
     * <p>Example request body:
     * <pre>{@code
     * {
     *   "de55Hex": "9F2608A1B2C3D4E5F6079F360200E29F10120110A000002A0000000000000000000000FF9C0100"
     * }
     * }</pre>
     * </p>
     *
     * @param request contains the raw DE 55 hex string
     * @return fully decoded tag list with ARQC/ATC fraud signals
     */
    @PostMapping("/emv/parse")
    public ResponseEntity<EmvParseResponse> parseEmv(@RequestBody EmvParseRequest request) {
        return ResponseEntity.ok(iso8583Service.parseEmv(request));
    }

    /**
     * GET /api/iso/transactions
     *
     * <p>Returns all active and historical transaction records tracked by the
     * in-memory Transaction State Store.</p>
     *
     * <p>Developer Note: Used by monitoring dashboards to inspect transaction
     * lifecycle states ({@code AUTHORISED}, {@code REVERSED}, {@code PARTIALLY_REVERSED}).</p>
     *
     * @return collection of tracked transaction records
     */
    @GetMapping("/transactions")
    public ResponseEntity<java.util.Collection<com.dean.iso8583.core.reversal.TransactionRecord>> getTransactions() {
        return ResponseEntity.ok(iso8583Service.getTransactions());
    }

    /**
     * GET /api/iso/echo/status
     *
     * <p>Returns real-time network management keep-alive telemetry and channel health status.</p>
     *
     * @return channel status report
     */
    @GetMapping("/echo/status")
    public ResponseEntity<ChannelStatusReport> getEchoStatus() {
        return ResponseEntity.ok(iso8583Service.getEchoStatus());
    }

    /**
     * POST /api/iso/echo/trigger
     *
     * <p>Executes an immediate on-demand ISO 8583 0800 Keep-Alive Echo Test request
     * and awaits the 0810 response.</p>
     *
     * @return echo execution result with latency and status
     */
    @PostMapping("/echo/trigger")
    public ResponseEntity<EchoResult> triggerEcho() {
        return ResponseEntity.ok(iso8583Service.triggerEcho());
    }

    /**
     * POST /api/iso/crypto/pin/encode
     *
     * <p>Encodes and encrypts a plaintext PIN into an ISO 9564 PIN block (DE 52).</p>
     */
    @PostMapping("/crypto/pin/encode")
    public ResponseEntity<PinEncodeResponse> encodePin(@RequestBody PinEncodeRequest request) {
        return ResponseEntity.ok(iso8583Service.encodePin(request));
    }

    /**
     * POST /api/iso/crypto/pin/translate
     *
     * <p>Translates an encrypted PIN block across different key zones or block formats (e.g. ISO-0 to ISO-1).</p>
     */
    @PostMapping("/crypto/pin/translate")
    public ResponseEntity<PinTranslateResponse> translatePin(@RequestBody PinTranslateRequest request) {
        return ResponseEntity.ok(iso8583Service.translatePin(request));
    }

    /**
     * POST /api/iso/crypto/mac/generate
     *
     * <p>Calculates an ISO 9797-1 Retail MAC for an ISO 8583 message.</p>
     */
    @PostMapping("/crypto/mac/generate")
    public ResponseEntity<MacGenerateResponse> generateMac(@RequestBody MacGenerateRequest request) {
        return ResponseEntity.ok(iso8583Service.generateMac(request));
    }

    /**
     * POST /api/iso/crypto/mac/verify
     *
     * <p>Verifies the ISO 9797-1 Retail MAC of an ISO 8583 message.</p>
     */
    @PostMapping("/crypto/mac/verify")
    public ResponseEntity<MacVerifyResponse> verifyMac(@RequestBody MacVerifyRequest request) {
        return ResponseEntity.ok(iso8583Service.verifyMac(request));
    }

    /**
     * POST /api/iso/clearing/batch/generate
     *
     * <p>Generates an end-of-day Dual-Message System (DMS) 1240 settlement clearing batch
     * with interchange fee calculation and reconciliation control totals.</p>
     */
    @PostMapping("/clearing/batch/generate")
    public ResponseEntity<com.dean.iso8583.core.clearing.ClearingBatch> generateClearingBatch(
            @RequestBody(required = false) ClearingBatchRequest request
    ) {
        return ResponseEntity.ok(iso8583Service.generateClearingBatch(request));
    }

    /**
     * POST /api/iso/clearing/chargeback
     *
     * <p>Files a 1440 Chargeback dispute against a previously authorized or settled transaction.</p>
     */
    @PostMapping("/clearing/chargeback")
    public ResponseEntity<com.dean.iso8583.core.clearing.ClearingRecord> fileChargeback(
            @RequestBody ChargebackRequest request
    ) {
        return ResponseEntity.ok(iso8583Service.fileChargeback(request));
    }

    /**
     * POST /api/iso/clearing/batch/parse
     *
     * <p>Parses an incoming raw batch clearing file string (Mastercard IPM or Visa BASE II format).</p>
     */
    @PostMapping("/clearing/batch/parse")
    public ResponseEntity<com.dean.iso8583.core.clearing.ClearingBatch> parseClearingBatch(
            @RequestBody ClearingParseRequest request
    ) {
        return ResponseEntity.ok(iso8583Service.parseClearingBatch(request));
    }

    /**
     * GET /api/iso/clearing/batches
     *
     * <p>Returns all generated and archived clearing batches.</p>
     */
    @GetMapping("/clearing/batches")
    public ResponseEntity<java.util.Collection<com.dean.iso8583.core.clearing.ClearingBatch>> getClearingBatches() {
        return ResponseEntity.ok(iso8583Service.getClearingBatches());
    }

    /**
     * GET /api/iso/clearing/chargebacks
     *
     * <p>Returns all filed chargeback dispute records.</p>
     */
    @GetMapping("/clearing/chargebacks")
    public ResponseEntity<java.util.Collection<com.dean.iso8583.core.clearing.ClearingRecord>> getChargebacks() {
        return ResponseEntity.ok(iso8583Service.getChargebacks());
    }

    /**
     * GET /api/iso/resiliency/status
     *
     * <p>Returns distributed persistence, distributed locking, and Kafka Outbox telemetry.</p>
     */
    @GetMapping("/resiliency/status")
    public ResponseEntity<com.dean.iso8583.web.data.dto.ResiliencyStatusResponse> getResiliencyStatus() {
        return ResponseEntity.ok(iso8583Service.getResiliencyStatus());
    }
}
