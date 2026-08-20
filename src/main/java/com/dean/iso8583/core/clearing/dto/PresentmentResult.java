package com.dean.iso8583.core.clearing.dto;

import lombok.Builder;

/**
 * Running totals for the presentment pass over transactions.
 */
@Builder
public record PresentmentResult(long grossCents, long interchangeCents, int count) {
}
