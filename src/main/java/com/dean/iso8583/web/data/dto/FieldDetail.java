package com.dean.iso8583.web.data.dto;

public record FieldDetail(
        int fieldId,
        String name,
        String type,
        String value
) {
}

