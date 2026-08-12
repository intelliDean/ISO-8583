package com.example.iso8583.core;

import java.util.ArrayList;
import java.util.List;

public class IsoUnpacker {

    /**
     * Unpacks a raw ASCII/Hex payload into a structured IsoMessage.
     * 
     * @param payload Raw ISO message string
     * @param hasHeader Whether the message starts with a 10-char TPDU header
     */
    public static IsoMessage unpack(String payload, boolean hasHeader) {
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("Payload cannot be null or empty");
        }

        IsoMessage msg = new IsoMessage();
        int offset = 0;

        // 1. Header (10 chars if present)
        if (hasHeader) {
            if (payload.length() < 10) {
                throw new IllegalArgumentException("Invalid payload: too short for 10-char header");
            }
            msg.setHeader(payload.substring(offset, offset + 10));
            offset += 10;
        }

        // 2. MTI (4 chars)
        if (payload.length() < offset + 4) {
            throw new IllegalArgumentException("Invalid payload: too short for MTI");
        }
        msg.setMti(payload.substring(offset, offset + 4));
        offset += 4;

        // 3. Primary Bitmap (16 hex chars = 64 bits)
        if (payload.length() < offset + 16) {
            throw new IllegalArgumentException("Invalid payload: too short for Primary Bitmap");
        }
        String primaryBitmapHex = payload.substring(offset, offset + 16);
        offset += 16;

        boolean[] activeFields = new boolean[129];
        parseBitmap(primaryBitmapHex, 1, activeFields);

        // 4. Secondary Bitmap (if Bit 1 is active)
        if (activeFields[1]) {
            if (payload.length() < offset + 16) {
                throw new IllegalArgumentException("Invalid payload: too short for Secondary Bitmap");
            }
            String secondaryBitmapHex = payload.substring(offset, offset + 16);
            offset += 16;
            parseBitmap(secondaryBitmapHex, 65, activeFields);
        }

        // 5. Unpack active Data Elements in numerical order
        for (int fieldId = 2; fieldId <= 128; fieldId++) {
            if (!activeFields[fieldId]) {
                continue;
            }

            IsoFieldDef def = IsoSpec.getFieldDef(fieldId);
            if (def == null) {
                def = new IsoFieldDef(fieldId, "Field " + fieldId, IsoFieldType.LLVAR_ALPHA, 99, "Generic Field");
            }

            int valueLength = 0;
            switch (def.getType()) {
                case FIXED_NUMERIC:
                case FIXED_ALPHA:
                case BINARY_FIXED:
                    valueLength = def.getMaxLength();
                    break;

                case LLVAR_NUMERIC:
                case LLVAR_ALPHA:
                    if (payload.length() < offset + 2) {
                        throw new IllegalArgumentException("Truncated payload reading LLVAR length for DE " + fieldId);
                    }
                    valueLength = Integer.parseInt(payload.substring(offset, offset + 2));
                    offset += 2;
                    break;

                case LLLVAR_ALPHA:
                    if (payload.length() < offset + 3) {
                        throw new IllegalArgumentException("Truncated payload reading LLLVAR length for DE " + fieldId);
                    }
                    valueLength = Integer.parseInt(payload.substring(offset, offset + 3));
                    offset += 3;
                    break;
            }

            if (payload.length() < offset + valueLength) {
                throw new IllegalArgumentException(String.format("Truncated payload reading DE %d value (expected %d chars)", fieldId, valueLength));
            }

            String value = payload.substring(offset, offset + valueLength);
            offset += valueLength;
            msg.setField(fieldId, value);
        }

        return msg;
    }

    private static void parseBitmap(String hexBitmap, int startFieldId, boolean[] activeFields) {
        byte[] bytes = hexToBytes(hexBitmap);
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            for (int bit = 0; bit < 8; bit++) {
                boolean isSet = (b & (1 << (7 - bit))) != 0;
                int fieldId = startFieldId + (i * 8) + bit;
                if (fieldId <= 128) {
                    activeFields[fieldId] = isSet;
                }
            }
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
