package com.dean.iso8583.core.emv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Enterprise-grade BER-TLV Parser for ISO 8583 DE 55 (ICC System Related Data).
 *
 * <h2>What is BER-TLV?</h2>
 * BER-TLV (Basic Encoding Rules – Tag Length Value) is a binary encoding format
 * defined in ISO/IEC 8825-1 and adopted verbatim by the EMVCo specification.
 * Each "triplet" consists of:
 * <ol>
 *   <li><b>Tag</b>   — 1 or more bytes identifying the data element.</li>
 *   <li><b>Length</b> — 1 or more bytes stating how many bytes the value occupies.</li>
 *   <li><b>Value</b>  — the raw content bytes.</li>
 * </ol>
 *
 * <h2>Tag Encoding Rules (EMV Book 3, § 5.2)</h2>
 * <ul>
 *   <li>If bits 5-1 of the first byte are all 1 ({@code 0x1F}), the tag continues
 *       into subsequent bytes until a byte with bit 8 = 0 is reached.</li>
 *   <li>Bit 6 of the first byte indicates whether the TLV is <em>primitive</em>
 *       (0) or <em>constructed</em> (1). This parser flattens constructed TLVs.</li>
 * </ul>
 *
 * <h2>Length Encoding Rules (EMV Book 3, § 5.2)</h2>
 * <ul>
 *   <li>If the first length byte has bit 8 = 0, it directly represents the length.</li>
 *   <li>Otherwise, bits 7-1 give the number of subsequent bytes that together
 *       encode the actual length (long-form BER).</li>
 * </ul>
 *
 * <h2>Enterprise Relevance</h2>
 * <ul>
 *   <li>Parsing DE 55 is mandatory for authorising chip card (EMV) and contactless
 *       (NFC) transactions. The extracted ARQC ({@code 9F26}) must be validated
 *       against the card's session key before any approval is issued.</li>
 *   <li>The ATC ({@code 9F36}) must be compared with the issuer's stored
 *       last-seen value; a replay or decrement is a hard-decline trigger.</li>
 *   <li>This parser is intentionally stateless and thread-safe — safe for use
 *       inside virtual-thread-per-request dispatching (Spring Boot 4 / Loom).</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   EmvParseResult result = EmvTlvParser.parse(de55HexString);
 *   String arqc = result.getValue("9F26");  // ARQC for HSM validation
 *   String atc  = result.getValue("9F36");  // ATC for replay-attack detection
 * }</pre>
 */
public final class EmvTlvParser {

    // ── Bit masks & sentinel values ───────────────────────────────────────────

    /** Mask to isolate bits 5-1 of a tag's first byte. All-ones signals multi-byte tag. */
    private static final int TAG_MULTI_BYTE_MASK = 0x1F;

    /** Bit 8 of a tag continuation byte — if set the tag continues further. */
    private static final int TAG_CONTINUATION_BIT = 0x80;

    /** Bit 8 of a length byte — if set, long-form length encoding is in use. */
    private static final int LENGTH_LONG_FORM_BIT = 0x80;

    /** Mask to extract the count of subsequent length bytes in long-form encoding. */
    private static final int LENGTH_BYTE_COUNT_MASK = 0x7F;

    /**
     * Maximum permitted DE 55 length (bytes) before the parser aborts.
     *
     * Developer Note: The ISO 8583 field specification defines DE 55 as
     * LLLVAR with max 255 bytes. We enforce this at the parser level as a
     * first-line defence against maliciously crafted oversized payloads.
     */
    private static final int MAX_DE55_BYTES = 255;

    // Utility class — no instances
    private EmvTlvParser() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses a hex-encoded DE 55 field into an ordered list of {@link EmvTag} objects.
     *
     * <p>The hex string must have an even number of characters. Upper or lower-case
     * hex digits are both accepted. Leading/trailing whitespace is trimmed.</p>
     *
     * @param de55Hex hex string of the raw DE 55 bytes (e.g.
     *                {@code "9F2608A1B2C3D4E5F6079F3602001C..."})
     * @return {@link EmvParseResult} containing the raw hex and all decoded tags
     * @throws EmvParseException if the input is {@code null}, blank, has an odd
     *                           length, exceeds 255 bytes, or contains a malformed
     *                           TLV triplet (truncated tag, length, or value)
     */
    public static EmvParseResult parse(String de55Hex) {
        String cleanHex = validateAndNormalise(de55Hex);
        byte[] bytes = hexToBytes(cleanHex);
        List<EmvTag> tags = parseTlvStream(bytes);
        return new EmvParseResult(cleanHex.toUpperCase(), Collections.unmodifiableList(tags));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Validation
    // ─────────────────────────────────────────────────────────────────────────

    private static String validateAndNormalise(String de55Hex) {
        if (de55Hex == null || de55Hex.isBlank()) {
            throw new EmvParseException("DE 55 hex string must not be null or blank");
        }
        String trimmed = de55Hex.trim();
        if ((trimmed.length() & 1) != 0) {
            throw new EmvParseException(
                    "DE 55 hex string must have an even number of characters; got length %d"
                            .formatted(trimmed.length()));
        }
        int byteCount = trimmed.length() / 2;
        if (byteCount > MAX_DE55_BYTES) {
            throw new EmvParseException(
                    "DE 55 exceeds maximum permitted size of %d bytes (got %d)"
                            .formatted(MAX_DE55_BYTES, byteCount));
        }
        return trimmed;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TLV Stream Parser
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Main parse loop — iterates through the byte array consuming TLV triplets
     * until the stream is exhausted.
     *
     * Developer Note: We deliberately do NOT recurse into constructed (template)
     * TLVs by default — the flattened view simplifies downstream ARQC validation
     * and HSM integration where individual primitive tags are required.
     * Constructed templates (e.g. 70, 77) are preserved as opaque values.
     */
    private static List<EmvTag> parseTlvStream(byte[] bytes) {
        List<EmvTag> tags = new ArrayList<>();
        int cursor = 0;

        while (cursor < bytes.length) {

            // ── 1. Read Tag ──────────────────────────────────────────────────
            TagReadResult tagResult = readTag(bytes, cursor);
            cursor = tagResult.nextCursor();
            String tagHex = tagResult.tagHex();

            // ── 2. Read Length ───────────────────────────────────────────────
            LengthReadResult lengthResult = readLength(bytes, cursor, tagHex);
            cursor = lengthResult.nextCursor();
            int valueLength = lengthResult.length();

            // ── 3. Read Value ────────────────────────────────────────────────
            if (cursor + valueLength > bytes.length) {
                throw new EmvParseException(
                        "Tag %s claims value length %d but only %d bytes remain in stream"
                                .formatted(tagHex, valueLength, bytes.length - cursor));
            }
            byte[] valueBytes = new byte[valueLength];
            System.arraycopy(bytes, cursor, valueBytes, 0, valueLength);
            cursor += valueLength;

            String valueHex = bytesToHex(valueBytes);

            // ── 4. Resolve name & build record ───────────────────────────────
            EmvTagName knownTag = EmvTagName.fromTag(tagHex);
            String name = knownTag == EmvTagName.UNKNOWN
                    ? "Proprietary/Unknown (%s)".formatted(tagHex.toUpperCase())
                    : knownTag.getDescription();

            EmvTag emvTag = EmvTag.builder()
                    .tag(tagHex.toUpperCase())
                    .name(name)
                    .length(valueLength)
                    .value(valueHex.toUpperCase())
                    .description(knownTag == EmvTagName.UNKNOWN
                            ? "Unregistered EMV tag — may be network-specific"
                            : buildDescription(knownTag, valueHex))
                    .build();

            tags.add(emvTag);
        }

        return tags;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tag Reading
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads a BER-TLV tag starting at {@code cursor}.
     *
     * <p>Single-byte tags: Most EMV tags are 1 byte (e.g. {@code 9A}, {@code 82}).
     * Multi-byte tags: Tags like {@code 9F26} use 2+ bytes where the low 5 bits
     * of the first byte are all 1, signalling continuation.</p>
     */
    private static TagReadResult readTag(byte[] bytes, int cursor) {
        assertBytesAvailable(bytes, cursor, 1, "tag");

        int firstByte = bytes[cursor] & 0xFF;
        cursor++;

        StringBuilder tagBuilder = new StringBuilder(String.format("%02X", firstByte));

        // Multi-byte tag: low 5 bits of first byte are all 1
        if ((firstByte & TAG_MULTI_BYTE_MASK) == TAG_MULTI_BYTE_MASK) {
            // Keep reading while bit 8 of the continuation byte is 1
            byte next;
            do {
                assertBytesAvailable(bytes, cursor, 1, "multi-byte tag continuation");
                next = bytes[cursor++];
                tagBuilder.append(String.format("%02X", next & 0xFF));
            } while ((next & TAG_CONTINUATION_BIT) != 0);
        }

        return new TagReadResult(tagBuilder.toString(), cursor);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Length Reading
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads a BER-TLV length field starting at {@code cursor}.
     *
     * <p>Short form: single byte where bit 8 = 0 → length is bits 7-1.
     * Long form: first byte has bit 8 = 1; bits 7-1 tell how many
     * subsequent bytes encode the integer length value.</p>
     *
     * Developer Note: We cap the long-form byte count at 4 to prevent
     * crafted inputs from claiming a 2^32-byte value length.
     */
    private static LengthReadResult readLength(byte[] bytes, int cursor, String tagHex) {
        assertBytesAvailable(bytes, cursor, 1, "length for tag " + tagHex);

        int firstByte = bytes[cursor++] & 0xFF;

        if ((firstByte & LENGTH_LONG_FORM_BIT) == 0) {
            // Short form — the byte itself is the length
            return new LengthReadResult(firstByte, cursor);
        }

        // Long form — determine how many bytes encode the length
        int lengthByteCount = firstByte & LENGTH_BYTE_COUNT_MASK;
        if (lengthByteCount == 0) {
            // Indefinite form (BER) — not used in EMV; treat as zero-length
            throw new EmvParseException(
                    "Indefinite-form length encoding is not permitted in EMV for tag " + tagHex);
        }
        if (lengthByteCount > 4) {
            throw new EmvParseException(
                    "Long-form length byte count %d exceeds maximum of 4 for tag %s"
                            .formatted(lengthByteCount, tagHex));
        }

        assertBytesAvailable(bytes, cursor, lengthByteCount, "long-form length bytes for tag " + tagHex);

        int length = 0;
        for (int i = 0; i < lengthByteCount; i++) {
            length = (length << 8) | (bytes[cursor++] & 0xFF);
        }

        if (length < 0 || length > MAX_DE55_BYTES) {
            throw new EmvParseException(
                    "Tag %s declares invalid value length %d".formatted(tagHex, length));
        }

        return new LengthReadResult(length, cursor);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a contextual description string for well-known tags.
     * Adds interpreted meaning for critical tags (ATC, ARQC, etc.) to assist
     * with fraud analysis dashboards and audit logging.
     */
    private static String buildDescription(EmvTagName tag, String valueHex) {
        return switch (tag) {
            case ARQC ->
                    "Authorisation Request Cryptogram — must be validated by HSM before approval";
            case APPLICATION_TRANSACTION_COUNTER ->
                    "ATC=%d (decimal) — verify monotonically increasing; reject on replay"
                            .formatted(hexToInt(valueHex));
            case CRYPTOGRAM_INFORMATION_DATA ->
                    describeCid(valueHex);
            case ISSUER_APPLICATION_DATA ->
                    "Issuer-proprietary data including offline CVR counters";
            case CVM_RESULTS ->
                    describeCvmResults(valueHex);
            case TRANSACTION_TYPE ->
                    describeTransactionType(valueHex);
            default ->
                    tag.getDescription();
        };
    }

    /** Interprets the Cryptogram Information Data byte. */
    private static String describeCid(String valueHex) {
        if (valueHex == null || valueHex.length() < 2) return "CID: unknown";
        int cid = Integer.parseInt(valueHex.substring(0, 2), 16) & 0xFF;
        String type = switch (cid >> 6) {
            case 0b10 -> "ARQC (Authorisation Request)";
            case 0b01 -> "TC (Transaction Certificate — offline approved)";
            case 0b00 -> "AAC (Application Authentication Cryptogram — declined)";
            default   -> "RFU (Reserved for Future Use)";
        };
        return "CID: %s".formatted(type);
    }

    /** Interprets the 3-byte CVM Results field. */
    private static String describeCvmResults(String valueHex) {
        if (valueHex == null || valueHex.length() < 6) return "CVM Results: malformed";
        String method   = valueHex.substring(0, 2);
        String condition = valueHex.substring(2, 4);
        String result   = valueHex.substring(4, 6);
        String performed = switch (method.toUpperCase()) {
            case "1F" -> "Signature";
            case "3E" -> "Online PIN";
            case "42" -> "Offline encrypted PIN";
            case "44" -> "Offline plaintext PIN";
            case "3F" -> "No CVM performed";
            default   -> "Method 0x" + method;
        };
        String outcome = switch (result.toUpperCase()) {
            case "02" -> "Successful";
            case "01" -> "Failed";
            default   -> "Unknown (" + result + ")";
        };
        return "CVM: %s | Condition: 0x%s | Result: %s".formatted(performed, condition, outcome);
    }

    /** Maps the 1-byte Transaction Type code to a human-readable label. */
    private static String describeTransactionType(String valueHex) {
        if (valueHex == null || valueHex.isBlank()) return "Transaction Type: unknown";
        return switch (valueHex.toUpperCase()) {
            case "00" -> "Purchase";
            case "01" -> "Cash Withdrawal (ATM)";
            case "09" -> "Purchase with Cashback";
            case "20" -> "Refund";
            case "40" -> "Balance Inquiry";
            default   -> "Transaction Type 0x" + valueHex;
        };
    }

    /** Converts a hex string to its integer value (unsigned). */
    private static int hexToInt(String hex) {
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Asserts that at least {@code required} bytes are available from {@code cursor}. */
    private static void assertBytesAvailable(byte[] bytes, int cursor, int required, String context) {
        if (cursor + required > bytes.length) {
            throw new EmvParseException(
                    "Truncated TLV stream — expected %d byte(s) for %s but only %d remain"
                            .formatted(required, context, bytes.length - cursor));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hex ↔ Byte conversions
    // ─────────────────────────────────────────────────────────────────────────

    private static byte[] hexToBytes(String hex) {
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low  = Character.digit(hex.charAt(i + 1), 16);
            if (high == -1 || low == -1) {
                throw new EmvParseException(
                        "Invalid hex character at position %d in: %s".formatted(i, hex));
            }
            result[i / 2] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal result records (package-private for testability)
    // ─────────────────────────────────────────────────────────────────────────

    /** Intermediate result from reading a BER-TLV tag field. */
    record TagReadResult(String tagHex, int nextCursor) {}

    /** Intermediate result from reading a BER-TLV length field. */
    record LengthReadResult(int length, int nextCursor) {}
}
