package com.dean.iso8583.web.data.dto;

import com.dean.iso8583.core.crypto.PinBlockFormat;

/**
 * Response body for PIN block translation.
 *
 * @param translatedBlockHex newly re-encrypted PIN block under destination key and format
 * @param srcFormat          source format
 * @param dstFormat          destination format
 * @param success            status flag
 */
public record PinTranslateResponse(
        String translatedBlockHex,
        PinBlockFormat srcFormat,
        PinBlockFormat dstFormat,
        boolean success
) {
}
