package com.dean.iso8583.core;

import com.dean.iso8583.core.dto.IsoFieldDef;
import com.dean.iso8583.core.dto.IsoFieldType;
import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.dto.IsoSpecDefinition;

import java.util.Objects;

/**
 * Developer Note:
 * <p>Enterprise ISO 8583 Packet Serialization Engine.</p>
 * <p>Supports both static default packing and dynamic network packager specifications (e.g. Visa SMS, Mastercard IPM).</p>
 * 
 * Frame Format:
 * <p>[TPDU Header (Optional)][MTI (4 Chars)][Primary Bitmap (16 Hex Chars)][Secondary Bitmap (16 Hex Chars)][Data Elements...]</p>
 *
 * <ul>
 * Field Length Strategies:
 *
 * <li>FIXED_NUMERIC: Left zero-padded to field max length.</li>
 *  <li>FIXED_ALPHA / BINARY_FIXED: Right space-padded to field max length.</li>
 *  <li>LLVAR: 2-digit ASCII length prefix + field value.</li>
 *  <li>LLLVAR: 3-digit ASCII length prefix + field value.</li>
 * </ul>
 */
public final class IsoPacker {

    private static final int MTI_LENGTH = 4;
    private static final int MIN_FIELD_ID = 2;
    private static final int MAX_FIELD_ID = 128;

    private IsoPacker() {
        // Utility class
    }

    /**
     * Serializes an ISO 8583 message using the default ISO 8583:1987 packager specification.
     */
    public static String packToString(IsoMessage message) {
        return packToString(message, null);
    }

    /**
     * Serializes an ISO 8583 message using a specific network packager specification definition.
     *
     * @param message ISO 8583 message to serialize
     * @param spec    Network specification definition (or null to use standard IsoSpec)
     * @return Packed ASCII/Hex ISO payload string
     */
    public static String packToString(IsoMessage message, IsoSpecDefinition spec) {
        Objects.requireNonNull(message, "message cannot be null");

        StringBuilder packed = new StringBuilder();

        appendHeader(packed, message);
        appendMti(packed, message);
        appendPrimaryBitmap(packed, message);
        appendSecondaryBitmap(packed, message);
        appendDataElements(packed, message, spec);

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

    private static void appendDataElements(StringBuilder packed, IsoMessage message, IsoSpecDefinition spec) {
        message.getFields().forEach((fieldId, value) -> {
            validateFieldId(fieldId);
            IsoFieldDef definition = getFieldDefinition(fieldId, spec);
            packed.append(formatField(definition, value));
        });
    }

    private static IsoFieldDef getFieldDefinition(int fieldId, IsoSpecDefinition spec) {
        if (spec != null && spec.getFieldDef(fieldId) != null) {
            return spec.getFieldDef(fieldId);
        }
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
            case FIXED_ALPHA, BINARY_FIXED -> formatFixedAlpha(definition, value);
            case LLVAR_NUMERIC, LLVAR_ALPHA -> formatVariableLength(definition, value, 2);
            case LLLVAR_ALPHA -> formatVariableLength(definition, value, 3);
        };
    }

    private static String formatFixedNumeric(IsoFieldDef definition, String value) {
        validateLength(definition, value);
        return padLeftWithZeros(value, definition.maxLength());
    }

    private static String formatFixedAlpha(IsoFieldDef definition, String value) {
        validateLength(definition, value);
        return padRightWithSpaces(value, definition.maxLength());
    }

    private static String formatVariableLength(IsoFieldDef definition, String value, int lengthDigits) {
        validateLength(definition, value);
        String lengthPrefix = formatLengthPrefix(value.length(), lengthDigits);
        return lengthPrefix + value;
    }

    private static String formatLengthPrefix(int length, int digits) {
        return String.format("%0" + digits + "d", length);
    }

    private static String padLeftWithZeros(String value, int totalLength) {
        return String.format("%" + totalLength + "s", value).replace(' ', '0');
    }

    private static String padRightWithSpaces(String value, int totalLength) {
        return String.format("%-" + totalLength + "s", value);
    }

    private static void validateMti(String mti) {
        if (mti == null || mti.length() != MTI_LENGTH) {
            throw new IllegalArgumentException("MTI must be exactly 4 characters");
        }
    }

    private static void validateFieldId(int fieldId) {
        if (fieldId < MIN_FIELD_ID || fieldId > MAX_FIELD_ID) {
            throw new IllegalArgumentException(
                    "Field ID must be between %d and %d. Received: %d".formatted(MIN_FIELD_ID, MAX_FIELD_ID, fieldId)
            );
        }
    }

    private static void validateLength(IsoFieldDef definition, String value) {
        if (value.length() > definition.maxLength()) {
            throw new IllegalArgumentException(
                    "Field %d (%s) exceeds maximum length of %d. Actual length: %d"
                            .formatted(definition.fieldId(), definition.name(), definition.maxLength(), value.length())
            );
        }
    }
}
