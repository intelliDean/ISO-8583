package com.dean.iso8583.web.data.dto;

/**
 * Response body for MAC verification.
 *
 * @param valid            true if MAC matched; false otherwise
 * @param calculatedMacHex calculated MAC string
 * @param message          status message
 */
public record MacVerifyResponse(
        boolean valid,
        String calculatedMacHex,
        String message
) {
}
