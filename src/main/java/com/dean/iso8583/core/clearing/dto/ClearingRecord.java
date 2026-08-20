package com.dean.iso8583.core.clearing;

import lombok.Builder;

import java.time.Instant;

/**
 * Immutable clearing transaction record representing a single 1240/1440 line item in a settlement batch.
 *
 * <h2>Key Financial Fields</h2>
 * <ul>
 *   <li>{@code amountIso}: 12-digit ISO 8583 DE 4 transaction amount (e.g. "000000002550" = $25.50).</li>
 *   <li>{@code interchangeFeeIso}: 12-digit interchange fee credited to the issuer / debited from the acquirer.</li>
 *   <li>{@code settlementAmountIso}: Net amount settled across central bank / settlement network accounts.</li>
 *   <li>{@code disputeReasonCode}: Required on 1440 Chargebacks (e.g. "4837" = Fraud / No Cardholder Authorization).</li>
 * </ul>
 */
@Builder
public record ClearingRecord(
        String recordId,
        ClearingRecordType recordType,
        String maskedPan,
        String processingCode,
        String amountIso,
        String interchangeFeeIso,
        String settlementAmountIso,
        String currencyCode,
        String rrn,
        String authCode,
        String stan,
        String terminalId,
        String merchantId,
        String disputeReasonCode,
        String rawPackedIso,
        Instant timestamp
) {
    public static ClearingRecord fromAuthorisation(
            String recordId,
            String maskedPan,
            String amountIso,
            String interchangeFeeIso,
            String settlementAmountIso,
            String currencyCode,
            String rrn,
            String authCode,
            String stan,
            String terminalId,
            String merchantId,
            String rawPackedIso
    ) {
        return ClearingRecord.builder()
                .recordId(recordId)
                .recordType(ClearingRecordType.FIRST_PRESENTMENT)
                .maskedPan(maskedPan)
                .processingCode("000000")
                .amountIso(amountIso)
                .interchangeFeeIso(interchangeFeeIso)
                .settlementAmountIso(settlementAmountIso)
                .currencyCode(currencyCode)
                .rrn(rrn)
                .authCode(authCode)
                .stan(stan)
                .terminalId(terminalId)
                .merchantId(merchantId)
                .disputeReasonCode(null)
                .rawPackedIso(rawPackedIso)
                .timestamp(Instant.now())
                .build();
    }

    public static ClearingRecord createChargeback(
            String recordId,
            String maskedPan,
            String amountIso,
            String currencyCode,
            String rrn,
            String authCode,
            String stan,
            String disputeReasonCode,
            String rawPackedIso
    ) {
        return ClearingRecord.builder()
                .recordId(recordId)
                .recordType(ClearingRecordType.CHARGEBACK)
                .maskedPan(maskedPan)
                .processingCode("000000")
                .amountIso(amountIso)
                .interchangeFeeIso("000000000000")
                .settlementAmountIso(amountIso)
                .currencyCode(currencyCode)
                .rrn(rrn)
                .authCode(authCode)
                .stan(stan)
                .terminalId(null)
                .merchantId(null)
                .disputeReasonCode(disputeReasonCode)
                .rawPackedIso(rawPackedIso)
                .timestamp(Instant.now())
                .build();
    }
}
