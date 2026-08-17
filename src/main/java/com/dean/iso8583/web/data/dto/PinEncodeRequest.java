package com.dean.iso8583.web.data.dto;

import com.dean.iso8583.core.crypto.PinBlockFormat;

/**
 * Request body for encoding and encrypting a PIN block.
 *
 * @param pin     plaintext PIN (4-12 digits)
 * @param pan     Primary Account Number (required for Format 0 and Format 3)
 * @param format  target PIN block format (FORMAT_0, FORMAT_1, FORMAT_3, FORMAT_4)
 * @param keyHex  optional 16/24/32-byte hex key string (if null, uses default ZPK)
 * @param keyId   optional registered key identifier in CryptoKeyRegistry
 */
public record PinEncodeRequest(
        String pin,
        String pan,
        PinBlockFormat format,
        String keyHex,
        String keyId
) {
}
