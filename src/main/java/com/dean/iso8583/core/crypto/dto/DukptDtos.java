package com.dean.iso8583.core.crypto.dto;

/**
 * Developer Note:
 * Data Transfer Objects for DUKPT (ANSI X9.24) Key Derivation & PIN Decryption operations.
 */
public final class DukptDtos {

    private DukptDtos() {}

    public record DeriveIpekRequest(
            String bdkHex,
            String ksnHex
    ) {}

    public record DeriveIpekResponse(
            String bdkHex,
            String ksnHex,
            String maskedKsnHex,
            String ipekHex,
            String keySetId,
            String deviceId,
            long transactionCounter
    ) {}

    public record DeriveKeyRequest(
            String bdkHex,
            String ksnHex
    ) {}

    public record DeriveKeyResponse(
            String bdkHex,
            String ksnHex,
            String ipekHex,
            String transactionKeyHex,
            String pinKeyHex,      // PEK (variant 0x00000000000000FF00000000000000FF)
            String macKeyHex,      // MAK (variant 0x000000000000FF00000000000000FF00)
            String dataKeyHex,     // DEK (variant 0x0000000000FF00000000000000FF0000)
            String keySetId,
            String deviceId,
            long transactionCounter
    ) {}

    public record DecryptDukptPinRequest(
            String bdkHex,
            String ksnHex,
            String encryptedPinBlockHex,
            String pan,
            String format // "FORMAT_0", "FORMAT_1", "FORMAT_3"
    ) {}

    public record DecryptDukptPinResponse(
            String clearPin,
            String clearPinBlockHex,
            String encryptedPinBlockHex,
            String pinKeyHex,
            String ksnHex,
            long transactionCounter,
            String format,
            boolean success,
            String message
    ) {}
}
