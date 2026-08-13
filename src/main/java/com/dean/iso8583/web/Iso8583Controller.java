package com.dean.iso8583.web;

import com.dean.iso8583.core.dto.IsoFieldDef;
import com.dean.iso8583.core.dto.IsoSpecDefinition;
import com.dean.iso8583.web.data.dto.*;
import com.dean.iso8583.web.service.ISO8583Service;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Developer Note:
 * REST API Controller providing ISO 8583 message parsing, packing, dialect specification catalog,
 * host simulation, and EMV DE 55 BER-TLV decoding endpoints.
 *
 * EMV Endpoint:
 *  POST /api/iso/emv/parse — decodes raw DE 55 hex into structured TLV tags.
 *  Surfaces ARQC (9F26) and ATC (9F36) as top-level response fields for
 *  rapid fraud-signal consumption by downstream issuer systems.
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
}
