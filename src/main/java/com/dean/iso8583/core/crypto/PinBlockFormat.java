package com.dean.iso8583.core.crypto;

/**
 * Developer Note:
 * Standard ISO 9564 PIN Block Formats for financial transaction card security.
 *
 * <h2>Format Specifications</h2>
 * <ul>
 *   <li><b>FORMAT_0 (ISO-0 / ANSI X9.8 / VISA-1)</b>:
 *       PIN field ({@code 0 + L + PIN + F...F}) XORed with PAN field ({@code 0000 + PAN[3..14]}).
 *       Most widely used format in global ATM and POS acquirer/issuer networks.
 *       Binds the PIN cryptographically to the card account number to prevent card-swap attacks.</li>
 *   <li><b>FORMAT_1 (ISO-1 / ECI-1)</b>:
 *       PIN field ({@code 1 + L + PIN + Random Hex Padding}).
 *       Transaction-independent format used where the PAN is unknown or for PIN changes.</li>
 *   <li><b>FORMAT_3 (ISO-3)</b>:
 *       PIN field ({@code 3 + L + PIN + Random Hex (0-9/A-F)}) XORed with PAN field ({@code 0000 + PAN[3..14]}).
 *       Enhances ISO-0 by introducing pseudo-random entropy into the padding digits.</li>
 *   <li><b>FORMAT_4 (ISO-4)</b>:
 *       16-byte AES-based PIN block format defined in ISO 9564-1:2017 for AES-128/AES-256 PIN encryption.</li>
 * </ul>
 */
public enum PinBlockFormat {

    /** ISO 9564-1 Format 0 (ANSI X9.8 / Visa-1 / ISO-0) — PAN XORed block */
    FORMAT_0(8, "ISO-0 (ANSI X9.8 / Visa-1)"),

    /** ISO 9564-1 Format 1 (ISO-1 / ECI-1) — Random padded transaction-independent block */
    FORMAT_1(8, "ISO-1 (ECI-1)"),

    /** ISO 9564-1 Format 3 (ISO-3) — Enhanced entropy PAN XORed block */
    FORMAT_3(8, "ISO-3"),

    /** ISO 9564-1 Format 4 (ISO-4) — 16-byte AES PIN block */
    FORMAT_4(16, "ISO-4 (AES)");

    private final int blockSizeBytes;
    private final String description;

    PinBlockFormat(int blockSizeBytes, String description) {
        this.blockSizeBytes = blockSizeBytes;
        this.description = description;
    }

    public int getBlockSizeBytes() {
        return blockSizeBytes;
    }

    public int getBlockSizeHexChars() {
        return blockSizeBytes * 2;
    }

    public String getDescription() {
        return description;
    }
}
