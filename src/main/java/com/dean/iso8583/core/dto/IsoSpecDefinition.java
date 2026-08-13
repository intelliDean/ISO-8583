package com.dean.iso8583.core.dto;

import lombok.Builder;

import java.util.Map;

/**
 * Developer Note:
 * Dynamic Packager Specification Model representing a complete network dialect (e.g., ISO 8583:1987, Visa SMS, Mastercard IPM).
 * In enterprise payment switches, field length rules, padding, and data types vary by card network.
 * This immutable record allows field dictionaries and header framing parameters to be dynamically loaded from JSON/YAML configurations.
 */
@Builder
public record IsoSpecDefinition(
        String id,
        String name,
        String version,
        String description,
        int defaultHeaderLength,
        Map<Integer, IsoFieldDef> fields
) {
    public IsoFieldDef getFieldDef(int fieldId) {
        return fields != null ? fields.get(fieldId) : null;
    }
}
