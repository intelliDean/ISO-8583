package com.dean.iso8583.web.data.dto;

/**
 * Request body for filing a 1440 Chargeback dispute.
 *
 * @param stan              original transaction STAN
 * @param maskedPan         cardholder masked PAN
 * @param amountIso         12-digit dispute amount
 * @param disputeReasonCode reason code (e.g. "4837" Fraud, "4853" Recurring Transaction Cancelled)
 */
public record ChargebackRequest(
        String stan,
        String maskedPan,
        String amountIso,
        String disputeReasonCode
) {
}
