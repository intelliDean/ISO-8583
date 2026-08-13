package com.dean.iso8583.web.data.dto;

public record PackResult(
        String rawPayload,
        String primaryBitmapHex,
        String secondaryBitmapHex,
        int length
) {
}
