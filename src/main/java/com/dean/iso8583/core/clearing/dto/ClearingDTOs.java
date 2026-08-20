package com.dean.iso8583.core.clearing.dto;

import lombok.Builder;

public final class ClearingDTOs {

    private ClearingDTOs() {}

    /**
     * A single built presentment: the clearing record, its packed line, and cent amounts for totals.
     */
    @Builder
    public record PresentmentEntry(
            ClearingRecord record,
            String rawPacked,
            long amountCents,
            long feeCents
    ) {}

    /**
     * Running totals for the presentment pass over transactions.
     */
    @Builder
    public record PresentmentResult(
            long grossCents,
            long interchangeCents,
            int count
    ) {}

}
