package com.dean.iso8583.web.data.dto;

import lombok.Builder;

@Builder
public record SimulateResult(
        String requestPayload,
        String responsePayload,
        String responseMti,
        String responseCode,
        String responseCodeDescription,
        long roundtripMs,
        boolean success,
        String message
) {
}