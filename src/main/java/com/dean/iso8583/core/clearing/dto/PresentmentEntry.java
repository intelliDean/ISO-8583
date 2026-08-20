package com.dean.iso8583.core.clearing.dto;

import lombok.Builder;

/**
 * A single built presentment: the clearing record, its packed line, and cent amounts for totals.
 */
@Builder
public record PresentmentEntry(

        ClearingRecord record,
        String rawPacked,
        long amountCents,
        long feeCents
) {
}
