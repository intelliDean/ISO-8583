package com.dean.iso8583.web.data.dto;

/**
 * Request body for verifying the MAC of an ISO 8583 message.
 *
 * @param rawPayload  raw ISO 8583 message string
 * @param expectedMac 16-hex character MAC (optional if message contains DE 64/DE 128)
 * @param keyHex      16-byte MAC key hex (optional)
 * @param keyId       registered key ID (optional)
 */
public record MacVerifyRequest(
        String rawPayload,
        String expectedMac,
        String keyHex,
        String keyId
) {
}
