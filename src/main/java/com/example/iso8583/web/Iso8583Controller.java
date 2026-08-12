package com.example.iso8583.web;

import com.example.iso8583.core.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/iso")
@CrossOrigin(origins = "*")
public class Iso8583Controller {

    @GetMapping("/spec")
    public Map<Integer, IsoFieldDef> getCatalog() {
        return IsoSpec.getAllFieldDefs();
    }

    @PostMapping("/unpack")
    public UnpackResult unpackMessage(@RequestBody UnpackRequest request) {
        IsoMessage msg = IsoUnpacker.unpack(request.getPayload(), request.isHasHeader());

        List<FieldDetail> details = new ArrayList<>();
        List<Integer> activeFields = new ArrayList<>();

        for (Map.Entry<Integer, String> entry : msg.getFields().entrySet()) {
            int fieldId = entry.getKey();
            activeFields.add(fieldId);
            IsoFieldDef def = IsoSpec.getFieldDef(fieldId);
            String name = (def != null) ? def.getName() : "Custom Field";
            IsoFieldType type = (def != null) ? def.getType() : IsoFieldType.LLVAR_ALPHA;

            details.add(new FieldDetail(fieldId, name, type.name(), entry.getValue()));
        }

        return new UnpackResult(
                msg.getHeader(),
                msg.getMti(),
                getMtiDescription(msg.getMti()),
                msg.getPrimaryBitmapHex(),
                msg.getSecondaryBitmapHex(),
                activeFields,
                details
        );
    }

    @PostMapping("/pack")
    public PackResult packMessage(@RequestBody PackRequest request) {
        IsoMessage msg = new IsoMessage(request.getMti());
        msg.setHeader(request.getHeader());

        if (request.getFields() != null) {
            request.getFields().forEach((key, val) -> {
                int fieldId = Integer.parseInt(key.toString());
                msg.setField(fieldId, val);
            });
        }

        String rawPacked = IsoPacker.packToString(msg);
        return new PackResult(
                rawPacked,
                msg.getPrimaryBitmapHex(),
                msg.getSecondaryBitmapHex(),
                rawPacked.length()
        );
    }

    @PostMapping("/simulate")
    public SimulateResult simulateTransaction(@RequestBody SimulateRequest request) {
        long startTime = System.currentTimeMillis();
        String rawRequest = request.getRawPayload();

        try (Socket socket = new Socket("localhost", 8583);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream())) {

            byte[] reqBytes = rawRequest.getBytes(StandardCharsets.UTF_8);
            out.writeShort(reqBytes.length);
            out.write(reqBytes);
            out.flush();

            int respLength = in.readUnsignedShort();
            byte[] respBytes = new byte[respLength];
            in.readFully(respBytes);
            String rawResponse = new String(respBytes, StandardCharsets.UTF_8);

            long elapsed = System.currentTimeMillis() - startTime;
            
            boolean respHasHeader = false;
            if (rawResponse.length() >= 14 && !rawResponse.substring(0, 4).matches("^(01|02|04|08)\\d\\d$")) {
                if (rawResponse.substring(10, 14).matches("^(01|02|04|08)\\d\\d$")) {
                    respHasHeader = true;
                }
            }

            IsoMessage respMsg = IsoUnpacker.unpack(rawResponse, respHasHeader);

            String respCode = respMsg.getField(39);
            String respDesc = getResponseCodeDescription(respCode);

            return new SimulateResult(rawRequest, rawResponse, respMsg.getMti(), respCode, respDesc, elapsed, true, "Success");

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            return new SimulateResult(rawRequest, null, null, "ERR", e.getMessage(), elapsed, false, e.getMessage());
        }
    }

    private String getMtiDescription(String mti) {
        if (mti == null || mti.length() != 4) return "Unknown MTI";
        char versionChar = mti.charAt(0);
        char classChar = mti.charAt(1);
        char funcChar = mti.charAt(2);
        char origChar = mti.charAt(3);

        String ver = switch (versionChar) {
            case '0' -> "ISO 8583:1987";
            case '1' -> "ISO 8583:1993";
            case '2' -> "ISO 8583:2003";
            default -> "Custom Version";
        };

        String cls = switch (classChar) {
            case '1' -> "Authorization";
            case '2' -> "Financial";
            case '3' -> "File Action";
            case '4' -> "Reversal / Chargeback";
            case '5' -> "Reconciliation";
            case '8' -> "Network Management";
            default -> "Other Message";
        };

        String func = switch (funcChar) {
            case '0' -> "Request";
            case '1' -> "Response";
            case '2' -> "Advice";
            case '3' -> "Advice Response";
            default -> "Notification";
        };

        return String.format("%s %s %s", ver, cls, func);
    }

    private String getResponseCodeDescription(String code) {
        if (code == null) return "No Response Code";
        return switch (code) {
            case "00" -> "Approved / Successful";
            case "01" -> "Refer to Card Issuer";
            case "04" -> "Pick-Up Card (Hot Card)";
            case "05" -> "Do Not Honor";
            case "14" -> "Invalid Card Number (PAN)";
            case "51" -> "Insufficient Funds";
            case "54" -> "Expired Card";
            case "55" -> "Incorrect PIN";
            case "91" -> "Issuer Timeout / Down";
            case "96" -> "System Error";
            default -> "Response Code " + code;
        };
    }

    // DTO Classes
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UnpackRequest {
        private String payload;
        private boolean hasHeader;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UnpackResult {
        private String header;
        private String mti;
        private String mtiDescription;
        private String primaryBitmapHex;
        private String secondaryBitmapHex;
        private List<Integer> activeFields;
        private List<FieldDetail> fields;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class FieldDetail {
        private int fieldId;
        private String name;
        private String type;
        private String value;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PackRequest {
        private String header;
        private String mti;
        private Map<Object, String> fields;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PackResult {
        private String rawPayload;
        private String primaryBitmapHex;
        private String secondaryBitmapHex;
        private int length;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SimulateRequest {
        private String rawPayload;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SimulateResult {
        private String requestPayload;
        private String responsePayload;
        private String responseMti;
        private String responseCode;
        private String responseCodeDescription;
        private long roundtripMs;
        private boolean success;
        private String message;
    }
}
