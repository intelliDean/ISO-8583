package com.dean.iso8583.core.clearing.dto;

import lombok.Builder;

/**
 * Formatted control totals for the trailer line and batch construction.
 */
@Builder
public record ControlTotals(
        long grossCents,
        long interchangeCents,
        long netCents,
        int recordCount
) {

    public static ControlTotals of(long grossCents, long interchangeCents, int recordCount) {
        long netCents = Math.max(0, grossCents - interchangeCents);
        return new ControlTotals(grossCents, interchangeCents, netCents, recordCount);
    }

    public String grossIso() {
        return "%012d".formatted(grossCents);
    }

    public String feeIso() {
        return "%012d".formatted(interchangeCents);
    }

    public String netIso() {
        return "%012d".formatted(netCents);
    }
}
