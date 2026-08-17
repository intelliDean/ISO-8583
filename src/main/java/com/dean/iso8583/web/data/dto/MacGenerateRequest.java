package com.dean.iso8583.web.data.dto;

/**
 * Request body for generating an ISO 9797-1 Retail MAC over an ISO 8583 message.
 *
 * @param rawPayload raw ISO 8583 message string (or null if providing pack fields)
 * @param keyHex     16-byte MAC key hex (or null to use keyId)
 * @param keyId      registered MAC key identifier (e.g. "DEFAULT_MAK")
 */
public record MacGenerateRequest(
        String rawPayload,
        String keyHex,
        String keyId
) {
}
