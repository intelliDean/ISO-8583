package com.dean.iso8583.core.crypto;

import lombok.extern.slf4j.Slf4j;

/**
 * Enterprise ISO 9564 PIN Block Encoding, Decoding, and Translation Engine.
 *
 * <h2>Supported Formats (ISO 9564-1)</h2>
 * <ul>
 *   <li><b>Format 0 (ANSI X9.8 / ISO-0)</b>: {@code (0 || L || PIN || F...F) XOR (0000 || PAN[rightmost 12 digits excl. check digit])}</li>
 *   <li><b>Format 1 (ISO-1)</b>: {@code 1 || L || PIN || Random Hex Pad}</li>
 *   <li><b>Format 3 (ISO-3)</b>: {@code (3 || L || PIN || Random Hex Pad) XOR (0000 || PAN[rightmost 12 digits excl. check digit])}</li>
 *   <li><b>Format 4 (ISO-4)</b>: 16-byte AES-based dual-pass block</li>
 * </ul>
 *
 * <h2>Enterprise Relevance & PCI-DSS</h2>
 * <ul>
 *   <li><b>PCI-DSS 3.4 / 3.5 &amp; PCI-PTS</b>: Plaintext PINs must never be logged, persisted, or exposed outside
 *       the cryptographic module boundary.</li>
 *   <li><b>PIN Translation</b>: When an acquirer routes an authorization request (0200) to an issuing bank,
 *       the payment switch must translate the PIN block from the Acquirer Working Key (ZPK_acq) to the
 *       Issuer Working Key (ZPK_iss), and potentially convert the format (e.g. ISO-1 to ISO-0).
 *       {@link #translatePinBlock} performs this atomic translation securely.</li>
 * </ul>
 */
@Slf4j
public final class IsoPinBlockEngine {

    private IsoPinBlockEngine() {
        // Utility class
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PIN Block Encoding (Clear Formats)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Formats a clear PIN and PAN into an unencrypted ISO 9564 PIN block (16 or 32 hex characters).
     *
     * @param pin    plaintext PIN (4 to 12 digits)
     * @param pan    Primary Account Number (required for Format 0 and Format 3)
     * @param format target {@link PinBlockFormat}
     * @return 16-character (or 32-character for Format 4) uppercase hex string
     */
    public static String encodeClearPinBlock(String pin, String pan, PinBlockFormat format) {
        validatePin(pin);

        return switch (format) {
            case FORMAT_0 -> encodeFormat0(pin, pan);
            case FORMAT_1 -> encodeFormat1(pin);
            case FORMAT_3 -> encodeFormat3(pin, pan);
            case FORMAT_4 -> encodeFormat4(pin, pan);
        };
    }

    /**
     * Decodes and extracts the plaintext PIN from an unencrypted ISO 9564 PIN block.
     *
     * @param clearHexBlock 16-hex or 32-hex character clear PIN block
     * @param pan           Primary Account Number (required for Format 0 and Format 3)
     * @param format        {@link PinBlockFormat} of the block
     * @return clear numeric PIN string
     */
    public static String decodeClearPinBlock(String clearHexBlock, String pan, PinBlockFormat format) {
        if (clearHexBlock == null || clearHexBlock.length() != format.getBlockSizeHexChars()) {
            throw new IllegalArgumentException("Invalid clear PIN block length for %s: expected %d hex chars"
                    .formatted(format, format.getBlockSizeHexChars()));
        }

        return switch (format) {
            case FORMAT_0 -> decodeFormat0(clearHexBlock, pan);
            case FORMAT_1 -> decodeFormat1(clearHexBlock);
            case FORMAT_3 -> decodeFormat3(clearHexBlock, pan);
            case FORMAT_4 -> decodeFormat4(clearHexBlock, pan);
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Encrypted PIN Block Operations (DE 52)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Formats and encrypts a PIN block under a Zone PIN Key (ZPK / PEK).
     *
     * @param pin        plaintext PIN
     * @param pan        Primary Account Number
     * @param format     target {@link PinBlockFormat}
     * @param workingKey 8-byte Single-DES, 16/24-byte Triple-DES, or 16/32-byte AES key
     * @return 16-hex or 32-hex encrypted PIN block suitable for ISO 8583 DE 52
     */
    public static String encryptPin(String pin, String pan, PinBlockFormat format, byte[] workingKey) {
        String clearBlock = encodeClearPinBlock(pin, pan, format);
        byte[] clearBytes = CryptoUtils.hexToBytes(clearBlock);

        byte[] encryptedBytes;
        if (format == PinBlockFormat.FORMAT_4) {
            encryptedBytes = CryptoUtils.aesEncryptEcb(clearBytes, workingKey);
        } else {
            encryptedBytes = CryptoUtils.desEncryptEcb(clearBytes, workingKey);
        }

        return CryptoUtils.bytesToHex(encryptedBytes);
    }

    /**
     * Decrypts and decodes an encrypted PIN block (DE 52) under a Zone PIN Key (ZPK / PEK).
     *
     * @param encryptedHexBlock 16-hex or 32-hex encrypted PIN block
     * @param pan               Primary Account Number
     * @param format            {@link PinBlockFormat} of the block
     * @param workingKey        decryption key
     * @return plaintext PIN
     */
    public static String decryptPin(String encryptedHexBlock, String pan, PinBlockFormat format, byte[] workingKey) {
        byte[] cipherBytes = CryptoUtils.hexToBytes(encryptedHexBlock);

        byte[] clearBytes;
        if (format == PinBlockFormat.FORMAT_4) {
            clearBytes = CryptoUtils.aesDecryptEcb(cipherBytes, workingKey);
        } else {
            clearBytes = CryptoUtils.desDecryptEcb(cipherBytes, workingKey);
        }

        String clearHex = CryptoUtils.bytesToHex(clearBytes);
        return decodeClearPinBlock(clearHex, pan, format);
    }

    /**
     * Translates an encrypted PIN block from a source key/format to a destination key/format.
     *
     * <p>Developer Note: This is the core operation executed by an ISO 8583 payment switch when
     * bridging between Acquirer and Issuer security domains. The clear PIN is held only
     * in transient registers and never persisted or exposed.</p>
     *
     * @param encryptedHexBlock incoming DE 52 encrypted PIN block
     * @param srcPan            source PAN
     * @param srcFormat         source {@link PinBlockFormat}
     * @param srcKey            source working key (ZPK_acq)
     * @param dstPan            destination PAN (usually identical to srcPan)
     * @param dstFormat         destination {@link PinBlockFormat}
     * @param dstKey            destination working key (ZPK_iss)
     * @return translated and re-encrypted DE 52 PIN block
     */
    public static String translatePinBlock(
            String encryptedHexBlock,
            String srcPan,
            PinBlockFormat srcFormat,
            byte[] srcKey,
            String dstPan,
            PinBlockFormat dstFormat,
            byte[] dstKey
    ) {
        // 1. Decrypt incoming PIN block under source key
        String clearPin = decryptPin(encryptedHexBlock, srcPan, srcFormat, srcKey);

        // 2. Re-encode and encrypt under destination key and format
        return encryptPin(clearPin, dstPan, dstFormat, dstKey);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal Format Implementations
    // ─────────────────────────────────────────────────────────────────────────

    private static String encodeFormat0(String pin, String pan) {
        // PIN Field: 0 + L + PIN + F...F (16 hex chars)
        String pinFieldHex = String.format("0%X%s", pin.length(), pin);
        pinFieldHex = padRight(pinFieldHex, 16, 'F');

        // PAN Field: 0000 + 12 rightmost digits of PAN excluding the check digit (16 hex chars)
        String panFieldHex = buildPanField(pan);

        byte[] pinBytes = CryptoUtils.hexToBytes(pinFieldHex);
        byte[] panBytes = CryptoUtils.hexToBytes(panFieldHex);

        byte[] blockBytes = CryptoUtils.xor(pinBytes, panBytes);
        return CryptoUtils.bytesToHex(blockBytes);
    }

    private static String decodeFormat0(String hexBlock, String pan) {
        byte[] blockBytes = CryptoUtils.hexToBytes(hexBlock);
        byte[] panBytes = CryptoUtils.hexToBytes(buildPanField(pan));

        byte[] pinBytes = CryptoUtils.xor(blockBytes, panBytes);
        String pinFieldHex = CryptoUtils.bytesToHex(pinBytes);

        if (pinFieldHex.charAt(0) != '0') {
            throw new IllegalArgumentException("Invalid Format 0 PIN block: header digit is not '0'");
        }

        int pinLength = Character.digit(pinFieldHex.charAt(1), 16);
        if (pinLength < 4 || pinLength > 12) {
            throw new IllegalArgumentException("Invalid Format 0 PIN length indicator: " + pinLength);
        }

        return pinFieldHex.substring(2, 2 + pinLength);
    }

    private static String encodeFormat1(String pin) {
        // Format 1: 1 + L + PIN + Random Pad (16 hex chars)
        String prefix = String.format("1%X%s", pin.length(), pin);
        int padLength = 16 - prefix.length();
        String randomPad = CryptoUtils.generateRandomHex(padLength);
        return prefix + randomPad;
    }

    private static String decodeFormat1(String hexBlock) {
        if (hexBlock.charAt(0) != '1') {
            throw new IllegalArgumentException("Invalid Format 1 PIN block: header digit is not '1'");
        }
        int pinLength = Character.digit(hexBlock.charAt(1), 16);
        if (pinLength < 4 || pinLength > 12) {
            throw new IllegalArgumentException("Invalid Format 1 PIN length indicator: " + pinLength);
        }
        return hexBlock.substring(2, 2 + pinLength);
    }

    private static String encodeFormat3(String pin, String pan) {
        // Format 3: (3 + L + PIN + Random Pad) XOR (0000 + PAN[3..14])
        String prefix = String.format("3%X%s", pin.length(), pin);
        int padLength = 16 - prefix.length();
        String randomPad = CryptoUtils.generateRandomHex(padLength);
        String pinFieldHex = prefix + randomPad;

        String panFieldHex = buildPanField(pan);

        byte[] pinBytes = CryptoUtils.hexToBytes(pinFieldHex);
        byte[] panBytes = CryptoUtils.hexToBytes(panFieldHex);

        byte[] blockBytes = CryptoUtils.xor(pinBytes, panBytes);
        return CryptoUtils.bytesToHex(blockBytes);
    }

    private static String decodeFormat3(String hexBlock, String pan) {
        byte[] blockBytes = CryptoUtils.hexToBytes(hexBlock);
        byte[] panBytes = CryptoUtils.hexToBytes(buildPanField(pan));

        byte[] pinBytes = CryptoUtils.xor(blockBytes, panBytes);
        String pinFieldHex = CryptoUtils.bytesToHex(pinBytes);

        if (pinFieldHex.charAt(0) != '3') {
            throw new IllegalArgumentException("Invalid Format 3 PIN block: header digit is not '3'");
        }

        int pinLength = Character.digit(pinFieldHex.charAt(1), 16);
        if (pinLength < 4 || pinLength > 12) {
            throw new IllegalArgumentException("Invalid Format 3 PIN length indicator: " + pinLength);
        }

        return pinFieldHex.substring(2, 2 + pinLength);
    }

    private static String encodeFormat4(String pin, String pan) {
        // Format 4 (AES 16-byte block):
        // PIN Field: 4 + L + PIN + A...A (32 hex chars)
        String pinFieldHex = String.format("4%X%s", pin.length(), pin);
        pinFieldHex = padRight(pinFieldHex, 32, 'A');

        // PAN Field: PAN length (2 hex digits) + PAN + 0...0 (32 hex chars)
        String cleanPan = extractCleanPan(pan);
        String panFieldHex = String.format("%02X%s", cleanPan.length(), cleanPan);
        panFieldHex = padRight(panFieldHex, 32, '0');

        byte[] pinBytes = CryptoUtils.hexToBytes(pinFieldHex);
        byte[] panBytes = CryptoUtils.hexToBytes(panFieldHex);

        byte[] blockBytes = CryptoUtils.xor(pinBytes, panBytes);
        return CryptoUtils.bytesToHex(blockBytes);
    }

    private static String decodeFormat4(String hexBlock, String pan) {
        byte[] blockBytes = CryptoUtils.hexToBytes(hexBlock);

        String cleanPan = extractCleanPan(pan);
        String panFieldHex = String.format("%02X%s", cleanPan.length(), cleanPan);
        panFieldHex = padRight(panFieldHex, 32, '0');
        byte[] panBytes = CryptoUtils.hexToBytes(panFieldHex);

        byte[] pinBytes = CryptoUtils.xor(blockBytes, panBytes);
        String pinFieldHex = CryptoUtils.bytesToHex(pinBytes);

        if (pinFieldHex.charAt(0) != '4') {
            throw new IllegalArgumentException("Invalid Format 4 PIN block: header digit is not '4'");
        }

        int pinLength = Character.digit(pinFieldHex.charAt(1), 16);
        return pinFieldHex.substring(2, 2 + pinLength);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper Methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the standard 16-hex character PAN block:
     * {@code 0000 + 12 rightmost digits of PAN (excluding the check digit)}.
     * Example: PAN "4532015588991234" -> 12 digits before last '4' is "532015588991" -> "0000532015588991".
     */
    public static String buildPanField(String pan) {
        String cleanPan = extractCleanPan(pan);
        if (cleanPan.length() < 13) {
            throw new IllegalArgumentException("PAN must have at least 13 digits for ISO 9564 formatting: " + pan);
        }
        // Take 12 digits prior to the last digit (check digit)
        String pan12 = cleanPan.substring(cleanPan.length() - 13, cleanPan.length() - 1);
        return "0000" + pan12;
    }

    private static String extractCleanPan(String pan) {
        if (pan == null || pan.isBlank()) {
            throw new IllegalArgumentException("PAN cannot be null or empty for PAN-bound PIN block format");
        }
        return pan.replaceAll("\\D", "");
    }

    private static void validatePin(String pin) {
        if (pin == null || pin.length() < 4 || pin.length() > 12 || !pin.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("PIN must be between 4 and 12 numeric digits");
        }
    }

    private static String padRight(String s, int n, char c) {
        if (s.length() >= n) {
            return s.substring(0, n);
        }
        return s + String.valueOf(c).repeat(n - s.length());
    }
}
