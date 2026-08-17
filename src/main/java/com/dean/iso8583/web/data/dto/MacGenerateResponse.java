package com.dean.iso8583.web.data.dto;

/**
 * Response body containing the generated ISO 9797-1 Retail MAC.
 *
 * @param macHex    16-hex character MAC suitable for DE 64 or DE 128
 * @param algorithm cryptographic algorithm used
 * @param keyUsed   identifier of key used
 */
public record MacGenerateResponse(
        String macHex,
        String algorithm,
        String keyUsed
) {
}
