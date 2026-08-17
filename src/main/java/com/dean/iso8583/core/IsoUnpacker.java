package com.dean.iso8583.core;

import com.dean.iso8583.core.dto.IsoFieldDef;
import com.dean.iso8583.core.dto.IsoFieldType;
import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.dto.IsoSpecDefinition;
import com.dean.iso8583.core.dto.PayloadReader;
import org.springframework.util.StringUtils;

/**
 * Developer Note:
 * <p>Enterprise ISO 8583 Stream Parser & Unpacker Engine.</p>
 * <p>Supports both static default unpacking and dynamic network packager specifications (e.g. Visa SMS, Mastercard IPM).</p>
 *
 *<ol>
 * Parsing Algorithm:
 *
 * <li>Extract optional TPDU Transport Header (10 chars by default).</ul>
 *  <li>Extract 4-digit Message Type Identifier (MTI).</ul>
 * <li> Extract 16 Hex character (64 bits) Primary Bitmap.</ul>
 * <li> Check Bit 1: If active, extract 16 Hex character (64 bits) Secondary Bitmap (DE 65 - 128).</ul>
 * <li> Parse Data Elements in numerical sequence (2..128) according to spec length indicator rules.</ul>
 * </ol>
 */
public final class IsoUnpacker {

    private static final int HEADER_LENGTH = 10;
    private static final int MTI_LENGTH = 4;
    private static final int BITMAP_HEX_LENGTH = 16;
    private static final int PRIMARY_BITMAP_START_FIELD = 1;
    private static final int SECONDARY_BITMAP_START_FIELD = 65;
    private static final int MAX_FIELD_ID = 128;

    private IsoUnpacker() {
        // Utility class
    }

    /**
     * Unpacks a raw ASCII/Hex payload into a structured IsoMessage using default IsoSpec.
     *
     * @param payload   raw ISO message string
     * @param hasHeader whether the message starts with a 10-character TPDU header
     * @return unpacked ISO message
     */
    public static IsoMessage unpack(String payload, boolean hasHeader) {
        return unpack(payload, hasHeader, null);
    }

    /**
     * Unpacks a raw ASCII/Hex payload into a structured IsoMessage using a custom network spec definition.
     *
     * @param payload   raw ISO message string
     * @param hasHeader whether the message starts with a TPDU header
     * @param spec      custom IsoSpecDefinition (or null for default)
     * @return unpacked ISO message
     */
    public static IsoMessage unpack(String payload, boolean hasHeader, IsoSpecDefinition spec) {
        validatePayload(payload);

        PayloadReader reader = new PayloadReader(payload);
        IsoMessage message = new IsoMessage();

        readHeader(reader, message, hasHeader);
        readMti(reader, message);

        boolean[] activeFields = readBitmaps(reader);

        readDataElements(reader, message, activeFields, spec);

        return message;
    }

    private static void validatePayload(String payload) {
        if (!StringUtils.hasText(payload)) {
            throw new IllegalArgumentException("Payload cannot be null or empty");
        }
    }

    private static void readHeader(PayloadReader reader, IsoMessage message, boolean hasHeader) {
        if (!hasHeader) return;
        message.setHeader(reader.read(HEADER_LENGTH, "10-character TPDU header"));
    }

    private static void readMti(PayloadReader reader, IsoMessage message) {
        message.setMti(reader.read(MTI_LENGTH, "MTI"));
    }

    private static boolean[] readBitmaps(PayloadReader reader) {
        boolean[] activeFields = new boolean[MAX_FIELD_ID + 1];

        readBitmap(reader, PRIMARY_BITMAP_START_FIELD, activeFields, "Primary Bitmap");

        if (activeFields[PRIMARY_BITMAP_START_FIELD]) {
            readBitmap(reader, SECONDARY_BITMAP_START_FIELD, activeFields, "Secondary Bitmap");
        }
        return activeFields;
    }

    private static void readBitmap(
            PayloadReader reader, int startFieldId, boolean[] activeFields, String bitmapName) {
        String bitmapHex = reader.read(BITMAP_HEX_LENGTH, bitmapName);
        parseBitmap(bitmapHex, startFieldId, activeFields);
    }

    private static void readDataElements(
            PayloadReader reader, IsoMessage message, boolean[] activeFields, IsoSpecDefinition spec) {
        for (int fieldId = 2; fieldId <= MAX_FIELD_ID; fieldId++) {
            if (activeFields[fieldId]) {
                readDataElement(reader, message, fieldId, spec);
            }
        }
    }

    private static void readDataElement(
            PayloadReader reader, IsoMessage message, int fieldId, IsoSpecDefinition spec) {
        IsoFieldDef definition = getFieldDefinition(fieldId, spec);
        int valueLength = determineValueLength(reader, definition, fieldId);
        String value = reader.read(valueLength, "DE %d value".formatted(fieldId));
        message.setField(fieldId, value);
    }

    private static IsoFieldDef getFieldDefinition(int fieldId, IsoSpecDefinition spec) {
        if (spec != null && spec.getFieldDef(fieldId) != null) {
            return spec.getFieldDef(fieldId);
        }
        IsoFieldDef definition = IsoSpec.getFieldDef(fieldId);
        return definition != null ? definition : createGenericFieldDefinition(fieldId);
    }

    private static IsoFieldDef createGenericFieldDefinition(int fieldId) {
        return IsoFieldDef.builder()
                .fieldId(fieldId)
                .name("Field " + fieldId)
                .type(IsoFieldType.LLVAR_ALPHA)
                .maxLength(99)
                .description("Generic Field")
                .build();
    }

    private static int determineValueLength(PayloadReader reader, IsoFieldDef definition, int fieldId) {
        return switch (definition.type()) {
            case FIXED_NUMERIC, FIXED_ALPHA, BINARY_FIXED -> definition.maxLength();
            case LLVAR_NUMERIC, LLVAR_ALPHA -> readVariableLength(reader, 2, fieldId);
            case LLLVAR_ALPHA -> readVariableLength(reader, 3, fieldId);
        };
    }

    private static int readVariableLength(PayloadReader reader, int lengthDigits, int fieldId) {
        String length = reader.read(lengthDigits, "length prefix for DE %d".formatted(fieldId));
        try {
            return Integer.parseInt(length);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Invalid length prefix '%s' for DE %d".formatted(length, fieldId), exception
            );
        }
    }

    private static void parseBitmap(String hexBitmap, int startFieldId, boolean[] activeFields) {
        byte[] bytes = hexToBytes(hexBitmap);
        for (int byteIndex = 0; byteIndex < bytes.length; byteIndex++) {
            parseBitmapByte(bytes[byteIndex], byteIndex, startFieldId, activeFields);
        }
    }

    private static void parseBitmapByte(byte value, int byteIndex, int startFieldId, boolean[] activeFields) {
        for (int bit = 0; bit < 8; bit++) {
            int fieldId = calculateFieldId(startFieldId, byteIndex, bit);
            if (fieldId > MAX_FIELD_ID) return;
            activeFields[fieldId] = isBitSet(value, bit);
        }
    }

    private static int calculateFieldId(int startFieldId, int byteIndex, int bit) {
        return startFieldId + (byteIndex * 8) + bit;
    }

    private static boolean isBitSet(byte value, int bit) {
        return (value & (1 << (7 - bit))) != 0;
    }

    private static byte[] hexToBytes(String hex) {
        validateHex(hex);
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            bytes[i / 2] = hexByte(hex.charAt(i), hex.charAt(i + 1));
        }
        return bytes;
    }

    private static byte hexByte(char high, char low) {
        int highDigit = Character.digit(high, 16);
        int lowDigit = Character.digit(low, 16);
        if (highDigit == -1 || lowDigit == -1) {
            throw new IllegalArgumentException("Invalid hexadecimal character");
        }
        return (byte) ((highDigit << 4) | lowDigit);
    }

    private static void validateHex(String hex) {
        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("Hexadecimal value must contain an even number of characters");
        }
    }
}
