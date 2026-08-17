package com.dean.iso8583.core.utils;

import com.dean.iso8583.core.IsoUnpacker;
import com.dean.iso8583.core.dto.IsoMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.TreeMap;

@Slf4j
public final class IsoMessageSanitizer {

    private IsoMessageSanitizer() {
        // Utility class
    }

    /**
     *<p> Masks Primary Account Number (PAN) according to PCI-DSS rules:</p>
     *<p> Shows first 6 digits (BIN) and last 4 digits, replacing middle digits with asterisks.</p>
     * <p>Example: 4532015588991234 -> 453201******1234</p>
     */
    public static String maskPan(String pan) {

        if (pan == null || pan.isEmpty()) return "";

        String cleanPan = pan.trim();
        int len = cleanPan.length();

        if (len <= 10) {
            // Short PAN: mask all but last 4
            if (len <= 4) return "*".repeat(len);
            return "*".repeat(len - 4) + cleanPan.substring(len - 4);
        }
        // First 6, asterisks for middle, last 4
        String first6 = cleanPan.substring(0, 6);
        String last4 = cleanPan.substring(len - 4);
        int maskedLen = len - 10;
        return first6 + "*".repeat(maskedLen) + last4;
    }

    /**
     * <p>Masks Track 2 Data (DE 35).</p>
     * <p>Format: PAN = ExpirationDate + ServiceCode + DiscretionaryData</p>
     * <p>Example: 4532015588991234=26121010000 -> 453201******1234=****1010000</p>
     */
    public static String maskTrack2(String track2) {

        if (track2 == null || track2.isEmpty()) return "";

        int separatorIdx = track2.indexOf('=');
        if (separatorIdx == -1) {
            separatorIdx = track2.indexOf('D'); // Alternative separator character
        }
        if (separatorIdx != -1) {
            String panPart = track2.substring(0, separatorIdx);
            String rest = track2.substring(separatorIdx + 1);
            String maskedPan = maskPan(panPart);
            String maskedRest = rest.length() > 4 ? "****" + rest.substring(4) : "*".repeat(rest.length());
            return maskedPan + track2.charAt(separatorIdx) + maskedRest;
        }
        return maskPan(track2);
    }

    /**
     * Masks sensitive fields inside an IsoMessage object without mutating the original message.
     */
    public static IsoMessage sanitizeMessage(IsoMessage original) {

        if (original == null) return null;

        IsoMessage sanitized = new IsoMessage(original.getMti());
        sanitized.setHeader(original.getHeader());

        for (Map.Entry<Integer, String> entry : original.getFields().entrySet()) {
            int fieldId = entry.getKey();
            String value = entry.getValue();

            switch (fieldId) {
                case 2 -> sanitized.setField(2, maskPan(value));
                case 35 -> sanitized.setField(35, maskTrack2(value));
                case 45 -> sanitized.setField(45, "[TRACK 1 MASKED]");
                case 52 -> sanitized.setField(52, "[PIN BLOCK MASKED]");
                case 53 -> sanitized.setField(53, "[SECURITY INFO MASKED]");
                default -> sanitized.setField(fieldId, value);
            }
        }
        return sanitized;
    }

    /**
     * <p>Unpacks a raw ISO 8583 payload string, masks all sensitive PCI data elements,
     * and returns a clean sanitized summary string safe for logging.</p>
     */
    public static String sanitizePayloadForLogging(String rawPayload, boolean hasHeader) {

        if (rawPayload == null || rawPayload.isEmpty()) return "";

        try {
            IsoMessage msg = IsoUnpacker.unpack(rawPayload, hasHeader);
            IsoMessage sanitized = sanitizeMessage(msg);

            StringBuilder sb = new StringBuilder();
            if (sanitized.getHeader() != null) {
                sb.append("[Header: ").append(sanitized.getHeader()).append("] ");
            }
            sb.append("[MTI: ").append(sanitized.getMti()).append("] ");
            sb.append("Fields: ").append(sanitized.getFields());
            return sb.toString();
        } catch (Exception e) {
            log.warn("Could not unpack payload for sanitization logging: {}", e.getMessage());
            return "[UNPARSEABLE PAYLOAD LOG MASKED]";
        }
    }
}
