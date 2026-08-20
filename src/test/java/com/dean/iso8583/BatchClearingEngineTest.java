package com.dean.iso8583;

import com.dean.iso8583.core.clearing.BatchClearingEngine;
import com.dean.iso8583.core.clearing.dto.ClearingBatch;
import com.dean.iso8583.core.clearing.dto.ClearingRecord;
import com.dean.iso8583.core.clearing.utils.InterchangeFeeCalculator;
import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.event.InMemoryOutboxEventRepository;
import com.dean.iso8583.core.event.IsoEventPublisher;
import com.dean.iso8583.core.lock.InMemoryDistributedLockService;
import com.dean.iso8583.core.persistence.InMemoryClearingBatchRepository;
import com.dean.iso8583.core.persistence.InMemoryTransactionRepository;
import com.dean.iso8583.core.reversal.TransactionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BatchClearingEngine & Interchange Test Suite")
class BatchClearingEngineTest {

    private TransactionStore transactionStore;
    private BatchClearingEngine clearingEngine;

    @BeforeEach
    void setUp() {
        var txnRepo = new InMemoryTransactionRepository();
        var lockService = new InMemoryDistributedLockService();
        var outboxRepo = new InMemoryOutboxEventRepository();
        var eventPublisher = new IsoEventPublisher(outboxRepo, new ObjectMapper());
        var clearingRepo = new InMemoryClearingBatchRepository();

        transactionStore = new TransactionStore(txnRepo, lockService, eventPublisher);
        clearingEngine = new BatchClearingEngine(transactionStore, clearingRepo, lockService, eventPublisher);
    }

    private void recordSampleAuth(String stan, String pan, String amount) {
        IsoMessage req = new IsoMessage("0200");
        req.setHeader("6000000000");
        req.setField(2, pan);
        req.setField(3, "000000");
        req.setField(4, amount);
        req.setField(7, "0817220000");
        req.setField(11, stan);
        req.setField(41, "TERM0001");
        req.setField(42, "MERCHANT1234567");
        req.setField(49, "840");

        IsoMessage resp = new IsoMessage("0210");
        resp.setHeader("6000000000");
        resp.setField(37, "123456789012");
        resp.setField(38, "AUTH01");
        resp.setField(39, "00");

        transactionStore.recordAuthorisation(req, resp);
    }

    @Nested
    @DisplayName("InterchangeFeeCalculator Tests")
    class InterchangeTests {

        @Test
        @DisplayName("Calculates standard interchange fee accurately (1.5% + $0.10)")
        void shouldCalculateStandardFee() {
            // $100.00 (10000 cents): 100 * 0.015 + 0.10 = $1.60 = 160 cents = 000000000160
            String fee100 = InterchangeFeeCalculator.calculateFee("000000010000");
            assertThat(fee100).isEqualTo("000000000160");

            // $25.50 (2550 cents): 25.50 * 0.015 + 0.10 = $0.4825 -> $0.48 = 48 cents = 000000000048
            String fee25 = InterchangeFeeCalculator.calculateFee("000000002550");
            assertThat(fee25).isEqualTo("000000000048");
        }
    }

    @Nested
    @DisplayName("Batch Generation Tests")
    class BatchGenerationTests {

        @Test
        @DisplayName("Generates 1240 First Presentment batch with control trailer (1644)")
        void shouldGenerateClearingBatch() {
            recordSampleAuth("000101", "4532015588991234", "000000010000"); // $100.00 (Fee: $1.60)
            recordSampleAuth("000102", "4532015588995678", "000000005000"); // $50.00  (Fee: $0.85)

            ClearingBatch batch = clearingEngine.generateClearingBatch("MASTERCARD-IPM");

            assertThat(batch).isNotNull();
            assertThat(batch.presentmentCount()).isEqualTo(2);
            assertThat(batch.chargebackCount()).isZero();
            assertThat(batch.totalGrossAmountIso()).isEqualTo("000000015000"); // $150.00
            assertThat(batch.totalInterchangeFeeIso()).isEqualTo("000000000245"); // $1.60 + $0.85 = $2.45
            assertThat(batch.netSettlementAmountIso()).isEqualTo("000000014755"); // $150.00 - $2.45 = $147.55

            assertThat(batch.rawBatchFile()).contains("HDR:1644");
            assertThat(batch.rawBatchFile()).contains("TRL:1644");
            assertThat(batch.rawBatchFile()).contains("1240");
        }

        @Test
        @DisplayName("Handles 1440 Chargeback dispute filings")
        void shouldFileAndIncludeChargeback() {
            ClearingRecord cb = clearingEngine.fileChargeback(
                    "000999",
                    "453201******1234",
                    "000000002550",
                    "4837" // Fraud
            );

            assertThat(cb).isNotNull();
            assertThat(cb.recordType().getMti()).isEqualTo("1440");
            assertThat(cb.disputeReasonCode()).isEqualTo("4837");

            // Subsequent batch should include chargeback
            ClearingBatch batch = clearingEngine.generateClearingBatch("VISA-BASE2");
            assertThat(batch.chargebackCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Parses raw batch clearing file into structured records")
        void shouldParseClearingFile() {
            recordSampleAuth("000201", "4532015588991234", "000000003000");
            ClearingBatch originalBatch = clearingEngine.generateClearingBatch("MASTERCARD-IPM");

            ClearingBatch parsedBatch = clearingEngine.parseClearingFile(originalBatch.rawBatchFile());

            assertThat(parsedBatch).isNotNull();
            assertThat(parsedBatch.totalTransactions()).isGreaterThanOrEqualTo(1);
            assertThat(parsedBatch.presentmentCount()).isGreaterThanOrEqualTo(1);
        }
    }
}
