package com.dean.iso8583.core;

import com.dean.iso8583.core.dto.IsoFieldDef;
import com.dean.iso8583.core.dto.IsoFieldType;
import com.dean.iso8583.core.dto.IsoMessage;

import java.util.Objects;

public final class IsoPacker {

    private IsoPacker() {
        // Utility class
    }

    /**
     * Serializes an ISO 8583 message into its raw string representation.
     *
     * <pre>
     * [TPDU][MTI][Primary Bitmap][Secondary Bitmap][Data Elements]
     * </pre>
     *
     * @param message ISO 8583 message to serialize
     * @return packed ISO 8583 message
     */
    public static String packToString(IsoMessage message) {
        Objects.requireNonNull(message, "message cannot be null");

        StringBuilder packed = new StringBuilder();

        appendHeader(packed, message);
        appendMti(packed, message);
        appendPrimaryBitmap(packed, message);
        appendSecondaryBitmap(packed, message);
        appendDataElements(packed, message);

        return packed.toString();
    }

    private static void appendHeader(StringBuilder packed, IsoMessage message) {
        String header = message.getHeader();

        if (header != null && !header.isBlank()) {
            packed.append(header);
        }
    }

    private static void appendMti(StringBuilder packed, IsoMessage message) {
        String mti = message.getMti();

        validateMti(mti);

        packed.append(mti);
    }

    private static void appendPrimaryBitmap(StringBuilder packed, IsoMessage message) {
        packed.append(message.getPrimaryBitmapHex());
    }

    private static void appendSecondaryBitmap(StringBuilder packed, IsoMessage message) {
        if (message.hasSecondaryBitmap()) {
            packed.append(message.getSecondaryBitmapHex());
        }
    }

    private static void appendDataElements(StringBuilder packed, IsoMessage message) {

        message.getFields()
                .forEach((fieldId, value) -> {

                    validateFieldId(fieldId);

                    IsoFieldDef definition = getFieldDefinition(fieldId);

                    packed.append(formatField(definition, value));
                });
    }

    private static IsoFieldDef getFieldDefinition(int fieldId) {
        IsoFieldDef definition = IsoSpec.getFieldDef(fieldId);

        if (definition != null) {
            return definition;
        }

        return defaultFieldDefinition(fieldId);
    }

    private static IsoFieldDef defaultFieldDefinition(int fieldId) {
        return IsoFieldDef.builder()
                .fieldId(fieldId)
                .name("Field " + fieldId)
                .type(IsoFieldType.LLVAR_ALPHA)
                .maxLength(99)
                .description("Custom Field")
                .build();
    }

    private static String formatField(IsoFieldDef definition, String value) {
        Objects.requireNonNull(value, "Value cannot be null for field " + definition.fieldId());

        return switch (definition.type()) {
            case FIXED_NUMERIC -> formatFixedNumeric(definition, value);

            case FIXED_ALPHA -> formatFixedAlpha(definition, value);

            case BINARY_FIXED -> formatBinaryFixed(definition, value);

            case LLVAR_NUMERIC, LLVAR_ALPHA -> formatLlvar(definition, value);

            case LLLVAR_ALPHA -> formatLllvar(definition, value);

            default -> "Not yet implemented: " + definition.type();
        };
    }

    private static String formatFixedNumeric(IsoFieldDef definition, String value) {
        validateLength(definition, value);

        if (!value.matches("\\d+")) {
            throw new IllegalArgumentException(
                    "Field %d must contain only numeric characters".formatted(definition.fieldId())
            );
        }

        return "0".repeat(definition.maxLength() - value.length()) + value;
    }

    private static String formatFixedAlpha(IsoFieldDef definition, String value) {
        validateLength(definition, value);

        return value + " ".repeat(definition.maxLength() - value.length());
    }

    private static String formatBinaryFixed(IsoFieldDef definition, String value) {
        validateLength(definition, value);
        return value;
    }

    private static String formatLlvar(IsoFieldDef definition, String value) {
        validateVariableLength(definition, value, 2);

        return "%02d%s".formatted(value.length(), value);
    }

    private static String formatLllvar(IsoFieldDef definition, String value) {
        validateVariableLength(definition, value, 3);

        return "%03d%s".formatted(value.length(), value);
    }

    private static void validateLength(IsoFieldDef definition, String value) {
        if (value.length() > definition.maxLength()) {
            throw new IllegalArgumentException(
                    "Field %d (%s) exceeds maximum length of %d. Actual length: %d"
                            .formatted(definition.fieldId(), definition.name(), definition.maxLength(), value.length())
            );
        }
    }

    private static void validateVariableLength(IsoFieldDef definition, String value, int lengthDigits) {
        validateLength(definition, value);

        int maximumRepresentableLength =
                (int) Math.pow(10, lengthDigits) - 1;

        if (value.length() > maximumRepresentableLength) {
            throw new IllegalArgumentException(
                    "Field %d exceeds %d-digit length prefix capacity".formatted(definition.fieldId(), lengthDigits)
            );
        }
    }

    private static void validateMti(String mti) {
        if (mti == null || mti.length() != 4) {
            throw new IllegalArgumentException("MTI must be exactly 4 characters");
        }

        if (!mti.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("MTI must contain only numeric characters");
        }
    }

    private static void validateFieldId(int fieldId) {
        if (fieldId < 2 || fieldId > 128) {
            throw new IllegalArgumentException("Field ID must be between 2 and 128: %d".formatted(fieldId));
        }
    }
}


//public class IsoPacker {
//
//    /**
//     * Serializes an IsoMessage into a raw ASCII/Hex string representation.
//     */
//    public static String packToString(IsoMessage message) {
//        StringBuilder packed = new StringBuilder();
//
//        // 1. Header (TPDU)
//        if (message.getHeader() != null && !message.getHeader().isEmpty()) {
//            packed.append(message.getHeader());
//        }
//
//        // 2. MTI (4 chars)
//        if (message.getMti() == null || message.getMti().length() != 4) {
//            throw new IllegalArgumentException("MTI must be exactly 4 characters");
//        }
//        packed.append(message.getMti());
//
//        // 3. Primary Bitmap
//        String primaryBitmap = message.getPrimaryBitmapHex();
//        packed.append(primaryBitmap);
//
//        // 4. Secondary Bitmap (if present)
//        if (message.hasSecondaryBitmap()) {
//            packed.append(message.getSecondaryBitmapHex());
//        }
//
//        // 5. Data Elements
//        for (Map.Entry<Integer, String> entry : message.getFields().entrySet()) {
//            int fieldId = entry.getKey();
//            String value = entry.getValue();
//
//            IsoFieldDef fieldDef = IsoSpec.getFieldDef(fieldId);
//
//            if (fieldDef == null) {
//                // Default fallback if not defined in spec: treat as LLVAR
//                fieldDef = IsoFieldDef.builder()
//                        .fieldId(fieldId)
//                        .name("Field " + fieldId)
//                        .type(IsoFieldType.LLVAR_ALPHA)
//                        .maxLength(99)
//                        .description("Custom Field")
//                        .build();
//            }
//
//            packed.append(formatField(fieldDef, value));
//        }
//
//        return packed.toString();
//    }
//
//    private static String formatField(IsoFieldDef def, String value) {
//        if (value == null) value = "";
//
//        int len = Math.min(value.length(), def.maxLength());
//
//        return switch (def.type()) {
//            case FIXED_NUMERIC -> {
//                // Left zero pad to fixed max length
//                if (value.length() > def.maxLength()) {
//                    yield value.substring(0, def.maxLength());
//                }
//                yield String.format("%" + def.maxLength() + "s", value).replace(' ', '0');
//            }
//            case FIXED_ALPHA, BINARY_FIXED -> {
//                // Right space pad to fixed max length
//                if (value.length() > def.maxLength()) {
//                    yield value.substring(0, def.maxLength());
//                }
//                yield String.format("%-" + def.maxLength() + "s", value);
//            }
//            case LLVAR_NUMERIC, LLVAR_ALPHA -> {
//                // 2-digit length prefix
//                String trimmedVal = value.substring(0, len);
//                yield String.format("%02d%s", len, trimmedVal);
//            }
//            case LLLVAR_ALPHA -> {
//                // 3-digit length prefix
//                String lllTrimmedVal = value.substring(0, len);
//                yield String.format("%03d%s", len, lllTrimmedVal);
//            }
//            default -> value;
//        };
//    }
//}
