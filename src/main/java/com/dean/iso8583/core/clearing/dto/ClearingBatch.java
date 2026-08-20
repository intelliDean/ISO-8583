package com.dean.iso8583.core.clearing;

import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * Immutable container representing a complete end-of-day settlement batch.
 *
 * <h2>Reconciliation Control Totals (1644 Trailer)</h2>
 * <ul>
 *   <li>{@code totalTransactions}: count of presentment and chargeback line items.</li>
 *   <li>{@code totalGrossAmountIso}: cumulative gross transactional amount.</li>
 *   <li>{@code totalInterchangeFeeIso}: cumulative interchange fees credited/debited.</li>
 *   <li>{@code netSettlementAmountIso}: final net settlement position to be transferred.</li>
 * </ul>
 */
@Builder
public record ClearingBatch(
        String batchId,
        String settlementDate,
        String networkId,
        int totalTransactions,
        int presentmentCount,
        int chargebackCount,
        String totalGrossAmountIso,
        String totalInterchangeFeeIso,
        String netSettlementAmountIso,
        List<ClearingRecord> records,
        String rawBatchFile,
        Instant generatedAt
) {
}
