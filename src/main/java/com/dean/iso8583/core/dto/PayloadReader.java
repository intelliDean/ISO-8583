package com.dean.iso8583.core.dto;


public final class PayloadReader {

    private final String payload;
    private int offset;

    public PayloadReader(String payload) {
        this.payload = payload;
    }

    public String read(int length, String description) {
        ensureAvailable(length, description);

        String value = payload.substring(offset, offset + length);

        offset += length;

        return value;
    }

    public void ensureAvailable(int length, String description) {
        if (offset + length > payload.length()) {
            throw new IllegalArgumentException(
                    "Invalid payload: truncated while reading %s (expected %d characters)"
                            .formatted(description, length)
            );
        }
    }
}