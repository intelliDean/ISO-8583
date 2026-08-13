package com.dean.iso8583.core;


import com.dean.iso8583.core.dto.IsoFieldDef;
import com.dean.iso8583.core.dto.IsoFieldType;
import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.dto.PayloadReader;
import org.springframework.util.StringUtils;

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
     * Unpacks a raw ASCII/Hex payload into a structured IsoMessage.
     *
     * @param payload   raw ISO message string
     * @param hasHeader whether the message starts with a 10-character TPDU header
     * @return unpacked ISO message
     */
    public static IsoMessage unpack(String payload, boolean hasHeader) {
        validatePayload(payload);

        PayloadReader reader = new PayloadReader(payload);
        IsoMessage message = new IsoMessage();

        readHeader(reader, message, hasHeader);
        readMti(reader, message);

        boolean[] activeFields = readBitmaps(reader);

        readDataElements(reader, message, activeFields);

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

    private static void readBitmap(PayloadReader reader, int startFieldId, boolean[] activeFields, String bitmapName) {
        String bitmapHex = reader.read(BITMAP_HEX_LENGTH, bitmapName);
        parseBitmap(bitmapHex, startFieldId, activeFields);
    }

    private static void readDataElements(PayloadReader reader, IsoMessage message, boolean[] activeFields) {

        for (int fieldId = 2; fieldId <= MAX_FIELD_ID; fieldId++) {
            if (activeFields[fieldId]) {
                readDataElement(reader, message, fieldId);
            }
        }
    }

    private static void readDataElement(PayloadReader reader, IsoMessage message, int fieldId) {
        IsoFieldDef definition = getFieldDefinition(fieldId);

        int valueLength = determineValueLength(reader, definition, fieldId);

        String value = reader.read(valueLength, "DE %d value".formatted(fieldId));

        message.setField(fieldId, value);
    }

    private static IsoFieldDef getFieldDefinition(int fieldId) {
        IsoFieldDef definition = IsoSpec.getFieldDef(fieldId);

        return definition != null
                ? definition
                : createGenericFieldDefinition(fieldId);
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

            default -> -1;
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

//    private static final class PayloadReader {
//
//        private final String payload;
//        private int offset;
//
//        private PayloadReader(String payload) {
//            this.payload = payload;
//        }
//
//        private String read(int length, String description) {
//            ensureAvailable(length, description);
//
//            String value = payload.substring(offset, offset + length);
//
//            offset += length;
//
//            return value;
//        }
//
//        private void ensureAvailable(int length, String description) {
//            if (offset + length > payload.length()) {
//                throw new IllegalArgumentException(
//                        "Invalid payload: truncated while reading %s (expected %d characters)"
//                                .formatted(description, length)
//                );
//            }
//        }
//    }
//}


//public class IsoUnpacker {
//
//    /**
//     * Unpacks a raw ASCII/Hex payload into a structured IsoMessage.
//     *
//     * @param payload Raw ISO message string
//     * @param hasHeader Whether the message starts with a 10-char TPDU header
//     */
//    public static IsoMessage unpack(String payload, boolean hasHeader) {
//        if (payload == null || payload.isEmpty()) {
//            throw new IllegalArgumentException("Payload cannot be null or empty");
//        }
//
//        IsoMessage msg = new IsoMessage();
//        int offset = 0;
//
//        // 1. Header (10 chars if present)
//        if (hasHeader) {
//            if (payload.length() < 10) {
//                throw new IllegalArgumentException("Invalid payload: too short for 10-char header");
//            }
//            msg.setHeader(payload.substring(offset, offset + 10));
//            offset += 10;
//        }
//
//        // 2. MTI (4 chars)
//        if (payload.length() < offset + 4) {
//            throw new IllegalArgumentException("Invalid payload: too short for MTI");
//        }
//        msg.setMti(payload.substring(offset, offset + 4));
//        offset += 4;
//
//        // 3. Primary Bitmap (16 hex chars = 64 bits)
//        if (payload.length() < offset + 16) {
//            throw new IllegalArgumentException("Invalid payload: too short for Primary Bitmap");
//        }
//        String primaryBitmapHex = payload.substring(offset, offset + 16);
//        offset += 16;
//
//        boolean[] activeFields = new boolean[129];
//        parseBitmap(primaryBitmapHex, 1, activeFields);
//
//        // 4. Secondary Bitmap (if Bit 1 is active)
//        if (activeFields[1]) {
//            if (payload.length() < offset + 16) {
//                throw new IllegalArgumentException("Invalid payload: too short for Secondary Bitmap");
//            }
//            String secondaryBitmapHex = payload.substring(offset, offset + 16);
//            offset += 16;
//            parseBitmap(secondaryBitmapHex, 65, activeFields);
//        }
//
//        // 5. Unpack active Data Elements in numerical order
//        for (int fieldId = 2; fieldId <= 128; fieldId++) {
//            if (!activeFields[fieldId]) {
//                continue;
//            }
//
//            IsoFieldDef def = IsoSpec.getFieldDef(fieldId);
//            if (def == null) {
//                def = new IsoFieldDef(fieldId, "Field " + fieldId, IsoFieldType.LLVAR_ALPHA, 99, "Generic Field");
//            }
//
//            int valueLength = 0;
//            switch (def.type()) {
//                case FIXED_NUMERIC:
//                case FIXED_ALPHA:
//                case BINARY_FIXED:
//                    valueLength = def.maxLength();
//                    break;
//
//                case LLVAR_NUMERIC:
//                case LLVAR_ALPHA:
//                    if (payload.length() < offset + 2) {
//                        throw new IllegalArgumentException("Truncated payload reading LLVAR length for DE " + fieldId);
//                    }
//                    valueLength = Integer.parseInt(payload.substring(offset, offset + 2));
//                    offset += 2;
//                    break;
//
//                case LLLVAR_ALPHA:
//                    if (payload.length() < offset + 3) {
//                        throw new IllegalArgumentException("Truncated payload reading LLLVAR length for DE " + fieldId);
//                    }
//                    valueLength = Integer.parseInt(payload.substring(offset, offset + 3));
//                    offset += 3;
//                    break;
//            }
//
//            if (payload.length() < offset + valueLength) {
//                throw new IllegalArgumentException(String.format("Truncated payload reading DE %d value (expected %d chars)", fieldId, valueLength));
//            }
//
//            String value = payload.substring(offset, offset + valueLength);
//            offset += valueLength;
//            msg.setField(fieldId, value);
//        }
//
//        return msg;
//    }
//
//    private static void parseBitmap(String hexBitmap, int startFieldId, boolean[] activeFields) {
//        byte[] bytes = hexToBytes(hexBitmap);
//        for (int i = 0; i < bytes.length; i++) {
//            byte b = bytes[i];
//            for (int bit = 0; bit < 8; bit++) {
//                boolean isSet = (b & (1 << (7 - bit))) != 0;
//                int fieldId = startFieldId + (i * 8) + bit;
//                if (fieldId <= 128) {
//                    activeFields[fieldId] = isSet;
//                }
//            }
//        }
//    }
//
//    private static byte[] hexToBytes(String hex) {
//        int len = hex.length();
//        byte[] data = new byte[len / 2];
//        for (int i = 0; i < len; i += 2) {
//            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
//                    + Character.digit(hex.charAt(i + 1), 16));
//        }
//        return data;
//    }
//}
