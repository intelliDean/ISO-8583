package com.example.iso8583.core;

import java.util.Map;

public class IsoPacker {

    /**
     * Serializes an IsoMessage into a raw ASCII/Hex string representation.
     */
    public static String packToString(IsoMessage message) {
        StringBuilder packed = new StringBuilder();

        // 1. Header (TPDU)
        if (message.getHeader() != null && !message.getHeader().isEmpty()) {
            packed.append(message.getHeader());
        }

        // 2. MTI (4 chars)
        if (message.getMti() == null || message.getMti().length() != 4) {
            throw new IllegalArgumentException("MTI must be exactly 4 characters");
        }
        packed.append(message.getMti());

        // 3. Primary Bitmap
        String primaryBitmap = message.getPrimaryBitmapHex();
        packed.append(primaryBitmap);

        // 4. Secondary Bitmap (if present)
        if (message.hasSecondaryBitmap()) {
            packed.append(message.getSecondaryBitmapHex());
        }

        // 5. Data Elements
        for (Map.Entry<Integer, String> entry : message.getFields().entrySet()) {
            int fieldId = entry.getKey();
            String value = entry.getValue();

            IsoFieldDef fieldDef = IsoSpec.getFieldDef(fieldId);
            if (fieldDef == null) {
                // Default fallback if not defined in spec: treat as LLVAR
                fieldDef = new IsoFieldDef(fieldId, "Field " + fieldId, IsoFieldType.LLVAR_ALPHA, 99, "Custom Field");
            }

            packed.append(formatField(fieldDef, value));
        }

        return packed.toString();
    }

    private static String formatField(IsoFieldDef def, String value) {
        if (value == null) {
            value = "";
        }
        switch (def.getType()) {
            case FIXED_NUMERIC:
                // Left zero pad to fixed max length
                if (value.length() > def.getMaxLength()) {
                    return value.substring(0, def.getMaxLength());
                }
                return String.format("%" + def.getMaxLength() + "s", value).replace(' ', '0');

            case FIXED_ALPHA:
            case BINARY_FIXED:
                // Right space pad to fixed max length
                if (value.length() > def.getMaxLength()) {
                    return value.substring(0, def.getMaxLength());
                }
                return String.format("%-" + def.getMaxLength() + "s", value);

            case LLVAR_NUMERIC:
            case LLVAR_ALPHA:
                // 2-digit length prefix
                int len = Math.min(value.length(), def.getMaxLength());
                String trimmedVal = value.substring(0, len);
                return String.format("%02d%s", len, trimmedVal);

            case LLLVAR_ALPHA:
                // 3-digit length prefix
                int lllLen = Math.min(value.length(), def.getMaxLength());
                String lllTrimmedVal = value.substring(0, lllLen);
                return String.format("%03d%s", lllLen, lllTrimmedVal);

            default:
                return value;
        }
    }
}
