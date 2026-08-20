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
 * Exposes standardized telemetry:
 * - Transaction counters and latency histograms
 * - Cryptographic operations (PIN block, MAC, DUKPT)
 * - Dual-Message System Clearing & Settlement batch volume
 * - Active transaction ledgers and Transactional Outbox backlog gauges
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IsoMetrics {

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
        String status = !success ? "FAILURE" : ("00".equals(responseCode) ? "APPROVED" : "DECLINED");
        String safeMti = (mti != null && !mti.isBlank()) ? mti : "UNKNOWN";
        String safeRc = (responseCode != null && !responseCode.isBlank()) ? responseCode : "NA";
        String safeNet = (network != null && !network.isBlank()) ? network : "DEFAULT";

        Counter.builder("iso.transactions.total")
                .description("Total number of ISO 8583 messages processed by the switch")
                .tag("mti", safeMti)
                .tag("rc", safeRc)
                .tag("network", safeNet)
                .tag("status", status)
                .register(registry)
                .increment();

        Timer.builder("iso.transaction.duration")
                .description("End-to-end processing latency for ISO 8583 transactions")
                .tag("mti", safeMti)
                .tag("status", status)
                .publishPercentileHistogram()
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Records a cryptographic operation (PIN encoding/translation, Retail MAC, DUKPT key tree derivation).
     */
    public void recordCryptoOperation(String operation, String format, boolean success) {
        Counter.builder("iso.crypto.operations.total")
                .description("Cryptographic operations executed by the ISO security engine")
                .tag("operation", operation != null ? operation : "UNKNOWN")
                .tag("format", format != null ? format : "NA")
                .tag("status", success ? "SUCCESS" : "FAILURE")
                .register(registry)
                .increment();
    }

    /**
     * Records Dual-Message clearing batch generation.
     */
    public void recordClearingBatch(String network, int recordCount, boolean success) {
        String safeNet = (network != null && !network.isBlank()) ? network : "DEFAULT";
        Counter.builder("iso.clearing.batches.total")
                .description("Total number of 1240 presentment clearing batches generated")
                .tag("network", safeNet)
                .tag("status", success ? "SUCCESS" : "FAILURE")
                .register(registry)
                .increment();

        if (success && recordCount > 0) {
            Counter.builder("iso.clearing.records.total")
                    .description("Total presentment records formatted for interchange clearing")
                    .tag("network", safeNet)
                    .tag("type", "PRESENTMENT")
                    .register(registry)
                    .increment(recordCount);
        }
    }

    /**
     * Records a 1440 Chargeback dispute.
     */
    public void recordChargeback(String reasonCode, boolean success) {
        Counter.builder("iso.clearing.records.total")
                .description("Total chargeback dispute records filed")
                .tag("type", "CHARGEBACK")
                .tag("reason", reasonCode != null ? reasonCode : "4837")
                .tag("status", success ? "SUCCESS" : "FAILURE")
                .register(registry)
                .increment();
    }

    /**
     * Records a transaction reversal (0400/0410).
     */
    public void recordReversal(boolean success) {
        Counter.builder("iso.reversals.total")
                .description("Total 0400/0410 transaction reversal attempts")
                .tag("status", success ? "SUCCESS" : "FAILURE")
                .register(registry)
                .increment();
    }

    /**
     * Records an 0800 Keep-Alive Echo heartbeat.
     */
    public void recordEcho(boolean success, long durationMillis) {
        Counter.builder("iso.echo.heartbeats.total")
                .description("Proactive 0800 keep-alive echo heartbeat tests")
                .tag("status", success ? "SUCCESS" : "FAILURE")
                .register(registry)
                .increment();

        Timer.builder("iso.echo.duration")
                .description("Round-trip time for 0800/0810 keep-alive echo heartbeats")
                .tag("status", success ? "SUCCESS" : "FAILURE")
                .register(registry)
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Registers dynamic gauges monitoring active transaction state store and pending outbox queue depth.
     */
    public void registerStateGauges(
            Supplier<Number> transactionCountSupplier,
            Supplier<Number> pendingOutboxSupplier
    ) {
        Gauge.builder("iso.transactions.active.count", transactionCountSupplier)
                .description("Current number of tracked transactions in the state store")
                .register(registry);

        Gauge.builder("iso.outbox.pending.count", pendingOutboxSupplier)
                .description("Current backlog of pending transactional outbox events awaiting Kafka dispatch")
                .register(registry);
    }
}
