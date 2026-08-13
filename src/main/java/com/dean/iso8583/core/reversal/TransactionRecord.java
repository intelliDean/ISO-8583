package com.dean.iso8583.core.reversal;

import java.time.Instant;

/**
 * Immutable snapshot of a financial transaction stored in the {@link TransactionStore}.
 *
 * <h2>Keying Strategy</h2>
 * Transactions are keyed by a composite of:
 * <ol>
 *   <li>DE 11 — Systems Trace Audit Number (STAN) — unique per acquirer per day.</li>
 *   <li>DE 2  — Primary Account Number (PAN) — ties the STAN to a specific card.</li>
 * </ol>
 * The composite key prevents cross-card STAN collisions that would otherwise
 * occur in high-volume multi-acquirer environments.
 *
 * <h2>Enterprise Relevance</h2>
 * <ul>
 *   <li>All monetary amounts are stored in the ISO 8583 DE 4 format (implied
 *       2 decimal places, e.g. {@code "000000001000"} = $10.00) to avoid
 *       floating-point rounding issues in financial calculations.</li>
 *   <li>{@code maskedPan} is stored instead of the raw PAN to comply with
 *       PCI-DSS Requirement 3.4 (Render PAN unreadable anywhere it is stored).</li>
 *   <li>{@code reversedAmount} tracks partial reversal amounts independently
 *       from the original authorised amount, enabling settlement reconciliation.</li>
 * </ul>
 *
 * @param stan              DE 11 — Systems Trace Audit Number
 * @param maskedPan         DE 2  — PAN, masked per PCI-DSS (e.g. 453201******1234)
 * @param processingCode    DE 3  — Processing Code (e.g. 000000 = Purchase)
 * @param authorisedAmount  DE 4  — Original authorised amount (12-digit ISO format)
 * @param reversedAmount    DE 95 — Amount reversed so far (12-digit ISO format, may be null)
 * @param transmissionTime  DE 7  — Transmission Date &amp; Time (MMDDHHmmss)
 * @param rrn               DE 37 — Retrieval Reference Number
 * @param authCode          DE 38 — Authorisation Identification Response (auth code)
 * @param terminalId        DE 41 — Card Acceptor Terminal ID
 * @param merchantId        DE 42 — Card Acceptor ID Code (Merchant ID)
 * @param currencyCode      DE 49 — Transaction Currency Code (ISO 4217 numeric)
 * @param state             Current lifecycle state of this transaction
 * @param createdAt         Timestamp when the original authorisation was recorded
 * @param lastUpdatedAt     Timestamp of the most recent state change
 */
public record TransactionRecord(
        String stan,
        String maskedPan,
        String processingCode,
        String authorisedAmount,
        String reversedAmount,
        String transmissionTime,
        String rrn,
        String authCode,
        String terminalId,
        String merchantId,
        String currencyCode,
        TransactionState state,
        Instant createdAt,
        Instant lastUpdatedAt
) {
    /**
     * Creates an updated copy of this record with a new state and reversed amount.
     *
     * <p>Developer Note: Records are immutable in Java; this builder-style
     * method produces a new instance reflecting the post-reversal state, which
     * preserves the full audit chain without mutation.</p>
     *
     * @param newState       the new lifecycle state
     * @param reversedAmount the total amount reversed at the time of the update
     * @return a new {@link TransactionRecord} with updated state fields
     */
    public TransactionRecord withReversalApplied(TransactionState newState, String reversedAmount) {
        return new TransactionRecord(
                this.stan,
                this.maskedPan,
                this.processingCode,
                this.authorisedAmount,
                reversedAmount,
                this.transmissionTime,
                this.rrn,
                this.authCode,
                this.terminalId,
                this.merchantId,
                this.currencyCode,
                newState,
                this.createdAt,
                Instant.now()
        );
    }

    /**
     * Composite store key: STAN + masked PAN.
     * Ensures uniqueness across cards even when STANs are recycled daily.
     */
    public String compositeKey() {
        return "%s:%s".formatted(stan, maskedPan);
    }
}
