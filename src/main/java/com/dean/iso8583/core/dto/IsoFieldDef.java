package com.dean.iso8583.core.dto;

import lombok.*;

@Builder
public record IsoFieldDef(

        int fieldId,

        String name,

        IsoFieldType type,

        int maxLength,

        String description
) {
}