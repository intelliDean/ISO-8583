package com.dean.iso8583.core.reversal;

/**
 * Represents the lifecycle state of a financial transaction tracked by the
 * {@link TransactionStore}.
 *
 * <h2>State Transitions</h2>
 * <pre>
 *   AUTHORISED ──────────────────────────────► REVERSED
 *       │                                        (0400/0420 fully accepted)
 *       └──────────────────► PARTIALLY_REVERSED
 *                                (partial reversal via 0420)
 *                                     │
 *                                     └──────► REVERSED
 *                                              (subsequent full reversal)
 * </pre>
 *
 * <h2>Enterprise Relevance</h2>
 * <ul>
 *   <li>The issuer must maintain per-transaction state to prevent
 *       double-reversal attacks (reversing an already-reversed transaction
 *       could result in a financial loss to the acquirer).</li>
 *   <li>{@link #REVERSAL_PENDING} is used when a reversal advice ({@code 0420})
 *       has been received but has not yet been confirmed by the issuer host.
 *       This state drives any necessary retry logic.</li>
 *   <li>PCI-DSS requires that all state transitions are logged with a
 *       tamper-evident audit trail.</li>
 * </ul>
 */
public enum TransactionState {

    /**
     * The original authorisation ({@code 0200}/{@code 0100}) was approved.
     * The transaction is eligible for reversal.
     */
    AUTHORISED,
    /**
     * A reversal request ({@code 0400}) has been received but the issuer has
     * not yet confirmed it. Retry logic applies.
     *
     * <p>Developer Note: When this state persists beyond a configurable TTL,
     * the reversal advice ({@code 0420}) flow must be initiated.</p>
     */
    REVERSAL_PENDING,
    /**
     * The full authorised amount has been reversed.
     * No further reversals are permitted — any subsequent reversal attempt
     * for the same STAN must be declined with response code {@code 94}
     * (Duplicate Transaction).
     */
    REVERSED,
    /**
     * An amount less than the original authorisation has been reversed via
     * a partial reversal advice ({@code 0420}).
     * The remaining balance is still subject to capture.
     */
    PARTIALLY_REVERSED,
    /**
     * The original authorisation was declined.
     * Reversal is not applicable — attempting to reverse a declined
     * transaction must be responded to with code {@code 25} (Unable to
     * Locate Record).
     */
    DECLINED
}
