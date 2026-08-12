package com.example.iso8583.core;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class IsoFieldDef {

    private int fieldId;

    private String name;

    private IsoFieldType type;

    private int maxLength;

    private String description;
}
