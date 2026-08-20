package com.dean.iso8583;

import com.dean.iso8583.core.metrics.IsoMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IsoMetrics Prometheus & Micrometer Instrumentation Tests")
class IsoMetricsTest {

    private MeterRegistry meterRegistry;
    private IsoMetrics isoMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        isoMetrics = new IsoMetrics(meterRegistry);
    }

    @Test
    @DisplayName("Should record ISO transaction counters and timer metrics")
    void shouldRecordTransactionMetrics() {
        isoMetrics.recordTransaction("0200", "00", "VISA-SMS", 15_000_000L, true);
        isoMetrics.recordTransaction("0200", "51", "VISA-SMS", 12_000_000L, true);
        isoMetrics.recordTransaction("0800", "00", "DEFAULT", 5_000_000L, true);

        Counter approvedCounter = meterRegistry.find("iso.transactions.total")
                .tag("mti", "0200")
                .tag("rc", "00")
                .tag("status", "APPROVED")
                .counter();
        assertThat(approvedCounter).isNotNull();
        assertThat(approvedCounter.count()).isEqualTo(1.0);

        Counter declinedCounter = meterRegistry.find("iso.transactions.total")
                .tag("mti", "0200")
                .tag("rc", "51")
                .tag("status", "DECLINED")
                .counter();
        assertThat(declinedCounter).isNotNull();
        assertThat(declinedCounter.count()).isEqualTo(1.0);

        assertThat(meterRegistry.find("iso.transaction.duration").timer()).isNotNull();
    }

    @Test
    @DisplayName("Should record cryptographic operations counters")
    void shouldRecordCryptoOperations() {
        isoMetrics.recordCryptoOperation("PIN_ENCODE", "FORMAT_0", true);
        isoMetrics.recordCryptoOperation("DUKPT_KEY_DERIVE", "2TDEA", true);
        isoMetrics.recordCryptoOperation("DUKPT_PIN_DECRYPT", "FORMAT_0", true);
        isoMetrics.recordCryptoOperation("MAC_VERIFY", "ISO-9797-1-ALG3", true);

        Counter pinCounter = meterRegistry.find("iso.crypto.operations.total")
                .tag("operation", "PIN_ENCODE")
                .counter();
        assertThat(pinCounter).isNotNull();
        assertThat(pinCounter.count()).isEqualTo(1.0);

        Counter dukptCounter = meterRegistry.find("iso.crypto.operations.total")
                .tag("operation", "DUKPT_KEY_DERIVE")
                .counter();
        assertThat(dukptCounter).isNotNull();
        assertThat(dukptCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record clearing batches, presentments, and chargeback disputes")
    void shouldRecordClearingMetrics() {
        isoMetrics.recordClearingBatch("MASTERCARD-IPM", 10, true);
        isoMetrics.recordChargeback("4837", true);

        Counter batchCounter = meterRegistry.find("iso.clearing.batches.total")
                .tag("network", "MASTERCARD-IPM")
                .counter();
        assertThat(batchCounter).isNotNull();
        assertThat(batchCounter.count()).isEqualTo(1.0);

        Counter presentmentsCounter = meterRegistry.find("iso.clearing.records.total")
                .tag("type", "PRESENTMENT")
                .counter();
        assertThat(presentmentsCounter).isNotNull();
        assertThat(presentmentsCounter.count()).isEqualTo(10.0);

        Counter chargebackCounter = meterRegistry.find("iso.clearing.records.total")
                .tag("type", "CHARGEBACK")
                .tag("reason", "4837")
                .counter();
        assertThat(chargebackCounter).isNotNull();
        assertThat(chargebackCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should register and read dynamic active ledger and outbox gauges")
    void shouldRegisterAndSampleGauges() {
        AtomicInteger activeTxns = new AtomicInteger(42);
        AtomicInteger pendingOutbox = new AtomicInteger(7);

        isoMetrics.registerStateGauges(activeTxns::get, pendingOutbox::get);

        double txnsGauge = meterRegistry.get("iso.transactions.active.count").gauge().value();
        double outboxGauge = meterRegistry.get("iso.outbox.pending.count").gauge().value();

        assertThat(txnsGauge).isEqualTo(42.0);
        assertThat(outboxGauge).isEqualTo(7.0);

        activeTxns.set(100);
        assertThat(meterRegistry.get("iso.transactions.active.count").gauge().value()).isEqualTo(100.0);
    }
}
