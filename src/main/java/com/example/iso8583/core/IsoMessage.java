package com.example.iso8583.core;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

@Getter
@Setter
public class IsoMessage {
    private String header; // Optional Transport Header / TPDU (e.g. 6000000000)
    private String mti;    // Message Type Identifier (e.g. 0200, 0210, 0800, 0810)
    private SortedMap<Integer, String> fields = new TreeMap<>();

    public IsoMessage() {}

    public IsoMessage(String mti) {
        this.mti = mti;
    }

    public IsoMessage setField(int fieldId, String value) {
        if (fieldId < 2 || fieldId > 128) {
            throw new IllegalArgumentException("Field ID must be between 2 and 128");
        }
        fields.put(fieldId, value);
        return this;
    }

    public String getField(int fieldId) {
        return fields.get(fieldId);
    }

    public boolean hasField(int fieldId) {
        return fields.containsKey(fieldId);
    }

    public boolean hasSecondaryBitmap() {
        for (int id : fields.keySet()) {
            if (id > 64) {
                return true;
            }
        }
        return false;
    }

    /**
     * Constructs 64-bit Primary Bitmap as a 16-character hex string.
     * Bit 1 indicates whether Secondary Bitmap is present.
     */
    public String getPrimaryBitmapHex() {

        byte[] bitmapBytes = new byte[8];
        if (hasSecondaryBitmap()) {
            // Set Bit 1 (0x80 on byte 0)
            bitmapBytes[0] |= (byte) 0x80;
        }

        for (int fieldId : fields.keySet()) {
            if (fieldId >= 2 && fieldId <= 64) {
                int bitIndex = fieldId - 1; // 0-based bit index (bit 2 is index 1)
                int byteIndex = bitIndex / 8;
                int bitOffset = 7 - (bitIndex % 8);
                bitmapBytes[byteIndex] |= (byte) (1 << bitOffset);
            }
        }

        return bytesToHex(bitmapBytes);
    }

    /**
     * Constructs 64-bit Secondary Bitmap as a 16-character hex string.
     */
    public String getSecondaryBitmapHex() {
        if (!hasSecondaryBitmap()) {
            return null;
        }
        byte[] bitmapBytes = new byte[8];
        for (int fieldId : fields.keySet()) {
            if (fieldId >= 65 && fieldId <= 128) {
                int bitIndex = (fieldId - 65); // 0-based bit index for secondary bitmap
                int byteIndex = bitIndex / 8;
                int bitOffset = 7 - (bitIndex % 8);
                bitmapBytes[byteIndex] |= (byte) (1 << bitOffset);
            }
        }
        return bytesToHex(bitmapBytes);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();

        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
