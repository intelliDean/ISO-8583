package com.dean.iso8583.web.data.dto;

import java.util.Map;

public record PackRequest(
        String header,
        String mti,
        Map<Object, String> fields
) {
    public PackRequest {
        fields = fields == null
                ? Map.of()
                : Map.copyOf(fields);
    }
}


