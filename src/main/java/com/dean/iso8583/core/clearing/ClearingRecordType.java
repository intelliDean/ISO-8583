package com.dean.iso8583.core.clearing;

/**
 * Developer Note:
 * Standard ISO 8583 Dual-Message System (DMS) Clearing & Settlement Message Types.
 *
 * <h2>Clearing Lifecycle (DMS)</h2>
 * <pre>
 *   [Online Stage: 0100/0200 Auth]
 *               │
 *               ▼
 *   [Batch Stage 1: 1240 First Presentment] ─────► Issuer debits cardholder account
 *               │
 *               ▼ (If Disputed)
 *   [Batch Stage 2: 1440 Chargeback] ────────────► Issuer reverses funds to cardholder
 *               │
 *               ▼ (If Acquirer Disputes)
 *   [Batch Stage 3: 1240 Second Presentment] ────► Acquirer re-presents with evidence
 * </pre>
 */
public enum ClearingRecordType {

    /** 1240 – First Presentment (Acquirer submits captured transaction for settlement) */
    FIRST_PRESENTMENT("1240", "First Presentment"),

    /** 1240 – Second Presentment (Acquirer re-submits previously charged-back transaction) */
    SECOND_PRESENTMENT("1240", "Second Presentment"),

    /** 1440 – Chargeback (Issuer initiates financial dispute on behalf of cardholder) */
    CHARGEBACK("1440", "Chargeback"),

    /** 1440 – Chargeback Reversal (Issuer cancels an erroneous chargeback) */
    CHARGEBACK_REVERSAL("1440", "Chargeback Reversal"),

    /** 1740 – Fee Collection / Interchange Adjustment */
    FEE_COLLECTION("1740", "Fee Collection"),

    /** 1644 – File Header (Batch metadata, settlement date, network identifier) */
    FILE_HEADER("1644", "File Header"),

    /** 1644 – File Trailer (Batch reconciliation, control counts, net settlement amounts) */
    FILE_TRAILER("1644", "File Trailer");

    private final String mti;
    private final String description;

    ClearingRecordType(String mti, String description) {
        this.mti = mti;
        this.description = description;
    }

    public String getMti() {
        return mti;
    }

    public String getDescription() {
        return description;
    }
}
