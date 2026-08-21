package com.dean.iso8583.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Developer Note:
 * Production Micrometer and Prometheus operational metrics instrumentation engine for ISO 8583.
 *
 * <p>Exposes standardized telemetry:
 * <ul>
 *   <li>Transaction counters and latency histograms</li>
 *   <li>Cryptographic operations (PIN block, MAC, DUKPT)</li>
 *   <li>Dual-Message System Clearing &amp; Settlement batch volume</li>
 *   <li>Active transaction ledgers and Transactional Outbox backlog gauges</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IsoMetrics {

    // Metric names
    public static final String METRIC_TRANSACTIONS_TOTAL  = "iso.transactions.total";
    public static final String METRIC_TRANSACTION_DURATION = "iso.transaction.duration";
    public static final String METRIC_CRYPTO_OPERATIONS   = "iso.crypto.operations.total";
    public static final String METRIC_CLEARING_BATCHES     = "iso.clearing.batches.total";
    public static final String METRIC_CLEARING_RECORDS     = "iso.clearing.records.total";
    public static final String METRIC_REVERSALS_TOTAL     = "iso.reversals.total";
    public static final String METRIC_ECHO_HEARTBEATS     = "iso.echo.heartbeats.total";
    public static final String METRIC_ECHO_DURATION       = "iso.echo.duration";
    public static final String METRIC_ACTIVE_TXN_COUNT    = "iso.transactions.active.count";
    public static final String METRIC_OUTBOX_PENDING      = "iso.outbox.pending.count";

    // Tag names
    public static final String TAG_MTI      = "mti";
    public static final String TAG_RC       = "rc";
    public static final String TAG_NETWORK  = "network";
    public static final String TAG_STATUS   = "status";
    public static final String TAG_OP       = "operation";
    public static final String TAG_FORMAT   = "format";
    public static final String TAG_TYPE     = "type";
    public static final String TAG_REASON   = "reason";

    // Status tag values
    public static final String STATUS_SUCCESS  = "SUCCESS";
    public static final String STATUS_FAILURE  = "FAILURE";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_DECLINED = "DECLINED";

    private final MeterRegistry registry;

    /**
     * Records a processed ISO 8583 message and its execution duration.
     */
    public void recordTransaction(
            String mti,
            String responseCode,
            String network,
            long durationNanos,
            boolean success
    ) {
        String status = !success ? STATUS_FAILURE : ("00".equals(responseCode) ? STATUS_APPROVED : STATUS_DECLINED);
        String safeMti = (mti != null && !mti.isBlank()) ? mti : "UNKNOWN";
        String safeRc  = (responseCode != null && !responseCode.isBlank()) ? responseCode : "NA";
        String safeNet = (network != null && !network.isBlank()) ? network : "DEFAULT";

        Counter.builder(METRIC_TRANSACTIONS_TOTAL)
                .description("Total number of ISO 8583 messages processed by the switch")
                .tag(TAG_MTI, safeMti)
                .tag(TAG_RC, safeRc)
                .tag(TAG_NETWORK, safeNet)
                .tag(TAG_STATUS, status)
                .register(registry)
                .increment();

        Timer.builder(METRIC_TRANSACTION_DURATION)
                .description("End-to-end processing latency for ISO 8583 transactions")
                .tag(TAG_MTI, safeMti)
                .tag(TAG_STATUS, status)
                .publishPercentileHistogram()
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Records a cryptographic operation (PIN encoding/translation, Retail MAC, DUKPT key tree derivation).
     */
    public void recordCryptoOperation(String operation, String format, boolean success) {
        Counter.builder(METRIC_CRYPTO_OPERATIONS)
                .description("Cryptographic operations executed by the ISO security engine")
                .tag(TAG_OP, operation != null ? operation : "UNKNOWN")
                .tag(TAG_FORMAT, format != null ? format : "NA")
                .tag(TAG_STATUS, success ? STATUS_SUCCESS : STATUS_FAILURE)
                .register(registry)
                .increment();
    }

    /**
     * Records Dual-Message clearing batch generation.
     */
    public void recordClearingBatch(String network, int recordCount, boolean success) {
        String safeNet = (network != null && !network.isBlank()) ? network : "DEFAULT";
        Counter.builder(METRIC_CLEARING_BATCHES)
                .description("Total number of 1240 presentment clearing batches generated")
                .tag(TAG_NETWORK, safeNet)
                .tag(TAG_STATUS, success ? STATUS_SUCCESS : STATUS_FAILURE)
                .register(registry)
                .increment();

        if (success && recordCount > 0) {
            Counter.builder(METRIC_CLEARING_RECORDS)
                    .description("Total presentment records formatted for interchange clearing")
                    .tag(TAG_NETWORK, safeNet)
                    .tag(TAG_TYPE, "PRESENTMENT")
                    .register(registry)
                    .increment(recordCount);
        }
    }

    /**
     * Records a 1440 Chargeback dispute.
     */
    public void recordChargeback(String reasonCode, boolean success) {
        Counter.builder(METRIC_CLEARING_RECORDS)
                .description("Total chargeback dispute records filed")
                .tag(TAG_TYPE, "CHARGEBACK")
                .tag(TAG_REASON, reasonCode != null ? reasonCode : "4837")
                .tag(TAG_STATUS, success ? STATUS_SUCCESS : STATUS_FAILURE)
                .register(registry)
                .increment();
    }

    /**
     * Records a transaction reversal (0400/0410).
     */
    public void recordReversal(boolean success) {
        Counter.builder(METRIC_REVERSALS_TOTAL)
                .description("Total 0400/0410 transaction reversal attempts")
                .tag(TAG_STATUS, success ? STATUS_SUCCESS : STATUS_FAILURE)
                .register(registry)
                .increment();
    }

    /**
     * Records an 0800 Keep-Alive Echo heartbeat.
     */
    public void recordEcho(boolean success, long durationMillis) {
        Counter.builder(METRIC_ECHO_HEARTBEATS)
                .description("Proactive 0800 keep-alive echo heartbeat tests")
                .tag(TAG_STATUS, success ? STATUS_SUCCESS : STATUS_FAILURE)
                .register(registry)
                .increment();

        Timer.builder(METRIC_ECHO_DURATION)
                .description("Round-trip time for 0800/0810 keep-alive echo heartbeats")
                .tag(TAG_STATUS, success ? STATUS_SUCCESS : STATUS_FAILURE)
                .register(registry)
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Registers dynamic gauges monitoring active transaction state store and pending outbox queue depth.
     */
    public void registerStateGauges(
            Supplier<? extends Number> transactionCountSupplier,
            Supplier<? extends Number> pendingOutboxSupplier
    ) {
        Gauge.builder(METRIC_ACTIVE_TXN_COUNT, transactionCountSupplier)
                .description("Current number of tracked transactions in the state store")
                .register(registry);

        Gauge.builder(METRIC_OUTBOX_PENDING, pendingOutboxSupplier)
                .description("Current backlog of pending transactional outbox events awaiting Kafka dispatch")
                .register(registry);
    }
}
