package com.dean.iso8583.web.data.dto;

import java.util.List;

public record UnpackResult(
        String header,
        String mti,
        String mtiDescription,
        String primaryBitmapHex,
        String secondaryBitmapHex,
        List<Integer> activeFields,
        List<FieldDetail> fields
) {
}

