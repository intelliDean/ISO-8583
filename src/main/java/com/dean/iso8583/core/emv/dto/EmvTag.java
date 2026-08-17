package com.dean.iso8583.core.emv.dto;

import lombok.Builder;

/**
 * Developer Note:
 * <p>EMV BER-TLV (Basic Encoding Rules - Tag Length Value) Data Element representation.</p>
 * <p>Carried in ISO 8583 DE 55 (ICC System Related Data) for Chip Card & Contactless transactions.</p>
 */
@Builder
public record EmvTag(
        String tag,

        String name,

        int length,

        String value,

        String description
) {
}
