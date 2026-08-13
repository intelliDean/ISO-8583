package com.dean.iso8583.web;

import com.dean.iso8583.core.*;
import com.dean.iso8583.core.dto.IsoFieldDef;
import com.dean.iso8583.web.data.dto.*;
import com.dean.iso8583.web.service.ISO8583Service;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/iso")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class Iso8583Controller {

    private final ISO8583Service iso8583Service;

    @GetMapping("/spec")
    public Map<Integer, IsoFieldDef> getCatalog() {
        return IsoSpec.getAllFieldDefs();
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
