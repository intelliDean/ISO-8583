package com.dean.iso8583.web.data.dto;

import com.dean.iso8583.core.crypto.PinBlockFormat;

/**
 * Response body for PIN block encoding.
 *
 * @param clearBlockHex     16 or 32-hex clear PIN block (for testing/debug)
 * @param encryptedBlockHex encrypted PIN block (suitable for DE 52)
 * @param format            PIN block format used
 * @param keyUsed           name or status of key used for encryption
 */
public record PinEncodeResponse(
        String clearBlockHex,
        String encryptedBlockHex,
        PinBlockFormat format,
        String keyUsed
) {
}
