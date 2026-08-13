package com.dean.iso8583.web.data.dto;

public record UnpackRequest(
        String payload,
        boolean hasHeader
) {
}

