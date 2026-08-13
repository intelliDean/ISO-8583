package com.dean.iso8583.core.emv;

import lombok.Builder;

/**
 * Developer Note:
 * EMV BER-TLV (Basic Encoding Rules - Tag Length Value) Data Element representation.
 * Carried in ISO 8583 DE 55 (ICC System Related Data) for Chip Card & Contactless transactions.
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
