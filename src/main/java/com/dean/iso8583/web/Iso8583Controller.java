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
 * REST API Controller providing ISO 8583 message parsing, packing, dialect specification catalog, and host simulation endpoints.
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
}
