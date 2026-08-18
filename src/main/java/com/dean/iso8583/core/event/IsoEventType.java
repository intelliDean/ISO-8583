package com.dean.iso8583.core.event;

/**
 * Enumeration of domain event types emitted by the ISO 8583 Payment Engine.
 *
 * <p>These events are published to Kafka topics to trigger downstream workflows
 * such as core-banking debit/credit postings, merchant settlement, fraud detection,
 * and accounting general ledger updates.</p>
 */
public enum IsoEventType {

    /**
     * Emitted when a financial transaction (0200) is successfully approved (0210 with RC=00).
     */
    TRANSACTION_AUTHORISED,

    /**
     * Emitted when a financial transaction (0200) is declined by issuer / host.
     */
    TRANSACTION_DECLINED,

    /**
     * Emitted when a full or partial reversal (0400/0420) is successfully executed.
     */
    TRANSACTION_REVERSED,

    /**
     * Emitted when an end-of-day DMS clearing batch (1240 / 1644) is generated for card schemes.
     */
    CLEARING_BATCH_GENERATED,

    /**
     * Emitted when an issuer files a 1440 chargeback dispute against a presentment.
     */
    CHARGEBACK_FILED,

    /**
     * Emitted when a network management echo heartbeat (0800/0810) completes.
     */
    NETWORK_ECHO_EXECUTED,

    /**
     * Emitted when a cryptographic key (ZPK, MAK, BDK) is registered or rotated.
     */
    CRYPTO_KEY_ROTATED
}
