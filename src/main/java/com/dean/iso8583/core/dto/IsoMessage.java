package com.dean.iso8583.core.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

@Setter
@Getter
@NoArgsConstructor
public class IsoMessage {

    private String header;

    private String mti;

    private final SortedMap<Integer, String> fields = new TreeMap<>();


    public IsoMessage(String mti) {
        validateMti(mti);
        this.mti = mti;
    }

    public void setField(int fieldId, String value) {
        validateFieldId(fieldId);
        Objects.requireNonNull(value, "Field value cannot be null");

        fields.put(fieldId, value);
    }

    public String getField(int fieldId) {
        return fields.get(fieldId);
    }

    public boolean hasField(int fieldId) {
        return fields.containsKey(fieldId);
    }

    public boolean hasSecondaryBitmap() {
        return !fields.isEmpty() && fields.lastKey() > 64;
    }

    /**
     * Returns the primary bitmap as 16 hexadecimal characters.
     */
    public String getPrimaryBitmapHex() {
        byte[] bitmap = new byte[8];

        setSecondaryBitmapIndicator(bitmap);
        setPrimaryFields(bitmap);

        return bytesToHex(bitmap);
    }

    /**
     * Returns the secondary bitmap as 16 hexadecimal characters.
     *
     * @return secondary bitmap, or null when no secondary bitmap is required
     */
    public String getSecondaryBitmapHex() {

        if (!hasSecondaryBitmap()) return null;

        byte[] bitmap = new byte[8];

        setSecondaryFields(bitmap);

        return bytesToHex(bitmap);
    }

    private void setSecondaryBitmapIndicator(byte[] bitmap) {
        if (hasSecondaryBitmap()) {
            bitmap[0] |= (byte) 0x80;
        }
    }

    private void setPrimaryFields(byte[] bitmap) {
        fields.keySet().stream()
                .filter(this::isPrimaryField)
                .forEach(fieldId -> setBitmapBit(bitmap, fieldId - 1));
    }

    private void setSecondaryFields(byte[] bitmap) {
        fields.keySet().stream()
                .filter(this::isSecondaryField)
                .forEach(fieldId -> setBitmapBit(bitmap, fieldId - 65));
    }

    private boolean isPrimaryField(int fieldId) {
        return fieldId <= 64;
    }

    private boolean isSecondaryField(int fieldId) {
        return fieldId >= 65;
    }

    /**
     * Sets a bit using a zero-based bitmap index.
     *
     * ISO 8583 bitmaps are represented most-significant-bit first.
     */
    private static void setBitmapBit(byte[] bitmap, int bitIndex) {
        int byteIndex = bitIndex / 8;
        int bitOffset = 7 - (bitIndex % 8);

        bitmap[byteIndex] |= (byte) (1 << bitOffset);
    }

    private static void validateFieldId(int fieldId) {
        if (fieldId < 2 || fieldId > 128) {
            throw new IllegalArgumentException("Field ID must be between 2 and 128: %d".formatted(fieldId));
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

    private static String bytesToHex(byte[] bytes) {
        final char[] hex = "0123456789ABCDEF".toCharArray();

        char[] result = new char[bytes.length * 2];

        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;

            result[i * 2] = hex[value >>> 4];
            result[i * 2 + 1] = hex[value & 0x0F];
        }

        return new String(result);
    }
}