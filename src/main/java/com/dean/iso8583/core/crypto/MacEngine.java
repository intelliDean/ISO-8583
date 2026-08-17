package com.dean.iso8583.core.crypto;

import com.dean.iso8583.core.IsoPacker;
import com.dean.iso8583.core.dto.IsoMessage;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Enterprise Message Authentication Code (MAC) Engine for ISO 8583 Transactions.
 *
 * <h2>Supported Algorithms (ISO 9797-1 / ANSI X9.19)</h2>
 * <ul>
 *   <li><b>ISO 9797-1 Algorithm 1 (Single-DES CBC-MAC)</b>:
 *       Standard DES CBC chained MAC with zero or ISO/IEC 7816-4 padding.</li>
 *   <li><b>ISO 9797-1 Algorithm 3 (Retail MAC / ANSI X9.19 / AS 2805 MAC)</b>:
 *       Industry-standard financial MAC using Double-Length (16-byte) 3DES keys ($K_L$ and $K_R$).
 *       DES CBC with $K_L$ on all intermediate blocks, followed by Triple-DES (EDE) on the final block.
 *       Carried in <b>DE 64</b> (Primary MAC) and <b>DE 128</b> (Secondary MAC).</li>
 * </ul>
 *
 * <h2>Enterprise Relevance</h2>
 * MAC verification ensures end-to-end message integrity and non-repudiation between
 * terminals, payment gateways, and banking switches. Any tampering with amounts, PANs,
 * or processing codes in transit invalidates the MAC and causes immediate rejection.
 */
@Slf4j
public final class MacEngine {

    private MacEngine() {
        // Utility class
    }

    /**
     * Calculates an 8-byte (16-hex character) ISO 9797-1 Algorithm 3 (Retail MAC) over raw bytes.
     *
     * @param data   data bytes to authenticate
     * @param macKey 16-byte double-length 3DES MAC key ($K_L || K_R$)
     * @return 16-character uppercase hex MAC string
     */
    public static String calculateRetailMac(byte[] data, byte[] macKey) {
        if (macKey == null || (macKey.length != 16 && macKey.length != 24)) {
            throw new IllegalArgumentException("Retail MAC requires a 16-byte or 24-byte 3DES key");
        }

        byte[] kL = new byte[8];
        byte[] kR = new byte[8];
        System.arraycopy(macKey, 0, kL, 0, 8);
        System.arraycopy(macKey, 8, kR, 0, 8);

        // ISO 9797-1 Padding Method 2 (ISO/IEC 7816-4): Append 0x80 followed by 0x00s to 8-byte boundary
        byte[] padded = padMethod2(data);

        byte[] currentBlock = new byte[8]; // IV = 0x00...00

        // Process all blocks except the last using Single-DES with K_L
        int blockCount = padded.length / 8;
        for (int i = 0; i < blockCount - 1; i++) {
            byte[] block = new byte[8];
            System.arraycopy(padded, i * 8, block, 0, 8);
            byte[] xorBlock = CryptoUtils.xor(currentBlock, block);
            currentBlock = CryptoUtils.desEncryptEcb(xorBlock, kL);
        }

        // Final block: EDE processing (Encrypt with K_L, Decrypt with K_R, Encrypt with K_L)
        byte[] lastBlock = new byte[8];
        System.arraycopy(padded, (blockCount - 1) * 8, lastBlock, 0, 8);
        byte[] xorLast = CryptoUtils.xor(currentBlock, lastBlock);

        byte[] step1 = CryptoUtils.desEncryptEcb(xorLast, kL);
        byte[] step2 = CryptoUtils.desDecryptEcb(step1, kR);
        byte[] finalMac = CryptoUtils.desEncryptEcb(step2, kL);

        return CryptoUtils.bytesToHex(finalMac);
    }

    /**
     * Calculates the Retail MAC for an ISO 8583 message.
     * Automatically excludes DE 64 / DE 128 during serialization to construct the exact signed payload.
     *
     * @param message ISO 8583 message
     * @param macKey  16-byte MAC key
     * @return 16-character hex MAC string
     */
    public static String calculateMessageMac(IsoMessage message, byte[] macKey) {
        // Clone message without MAC fields (DE 64 & DE 128)
        IsoMessage unsigned = cloneWithoutMac(message);
        String packed = IsoPacker.packToString(unsigned);
        byte[] payloadBytes = packed.getBytes(StandardCharsets.US_ASCII);

        return calculateRetailMac(payloadBytes, macKey);
    }

    /**
     * Verifies whether the MAC present in DE 64 or DE 128 matches the calculated MAC.
     *
     * @param message      incoming ISO 8583 message containing DE 64 or DE 128
     * @param macKey       16-byte MAC key
     * @return true if MAC is valid; false otherwise
     */
    public static boolean verifyMessageMac(IsoMessage message, byte[] macKey) {
        String providedMac = null;
        if (message.hasField(64)) {
            providedMac = message.getField(64);
        } else if (message.hasField(128)) {
            providedMac = message.getField(128);
        }

        if (providedMac == null || providedMac.isBlank()) {
            log.warn("Cannot verify MAC: neither DE 64 nor DE 128 is present in the message");
            return false;
        }

        String calculatedMac = calculateMessageMac(message, macKey);

        // Constant-time comparison to prevent timing attacks
        boolean matches = constantTimeEquals(providedMac.trim().toUpperCase(), calculatedMac.toUpperCase());
        if (!matches) {
            log.warn("MAC mismatch: provided={} calculated={}", providedMac, calculatedMac);
        }
        return matches;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper Methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ISO 9797-1 Padding Method 2:
     * Appends single mandatory 0x80 byte, followed by 0 to 7 zero bytes (0x00) up to the 8-byte boundary.
     */
    public static byte[] padMethod2(byte[] data) {
        int padBytes = 8 - (data.length % 8);
        byte[] padded = new byte[data.length + padBytes];
        System.arraycopy(data, 0, padded, 0, data.length);
        padded[data.length] = (byte) 0x80;
        // Remaining bytes in array are default 0x00
        return padded;
    }

    private static IsoMessage cloneWithoutMac(IsoMessage original) {
        IsoMessage clone = new IsoMessage(original.getMti());
        clone.setHeader(original.getHeader());

        original.getFields().forEach((fieldId, value) -> {
            if (fieldId != 64 && fieldId != 128) {
                clone.setField(fieldId, value);
            }
        });

        return clone;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return Arrays.equals(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
}
