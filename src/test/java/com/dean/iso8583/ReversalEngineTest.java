package com.dean.iso8583;

import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.reversal.ReversalEngine;
import com.dean.iso8583.core.reversal.TransactionRecord;
import com.dean.iso8583.core.reversal.TransactionState;
import com.dean.iso8583.core.reversal.TransactionStore;
import com.dean.iso8583.server.IsoMessageProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Developer Note:
 * Enterprise test suite for ISO 8583 Reversal Engine & Transaction State Store.
 * Validates 0200 auth recording, 0400 synchronous reversal matching, duplicate
 * reversal prevention (RC 94), missing transaction handling (RC 25), and 0420
 * offline reversal advice unconditional acceptance & partial reversals (DE 95).
 */
@DisplayName("ReversalEngine & TransactionStore Test Suite")
class ReversalEngineTest {

    private TransactionStore transactionStore;
    private ReversalEngine reversalEngine;
    private IsoMessageProcessor messageProcessor;

    @BeforeEach
    void setUp() {
        transactionStore = new TransactionStore();
        reversalEngine = new ReversalEngine(transactionStore);
        messageProcessor = new IsoMessageProcessor(reversalEngine);
    }

    private IsoMessage createAuthRequest(String stan, String pan, String amount) {
        IsoMessage req = new IsoMessage("0200");
        req.setHeader("6000000000");
        req.setField(2, pan);
        req.setField(3, "000000");
        req.setField(4, amount);
        req.setField(7, "0814120000");
        req.setField(11, stan);
        req.setField(41, "TERM0001");
        req.setField(42, "MERCHANT1234567");
        req.setField(49, "840");
        return req;
    }

    private IsoMessage createReversalRequest(String mti, String stan, String pan, String amount) {
        IsoMessage req = new IsoMessage(mti);
        req.setHeader("6000000000");
        req.setField(2, pan);
        req.setField(3, "000000");
        req.setField(4, amount);
        req.setField(7, "0814120500");
        req.setField(11, stan);
        req.setField(41, "TERM0001");
        req.setField(42, "MERCHANT1234567");
        req.setField(49, "840");
        return req;
    }

    @Nested
    @DisplayName("TransactionStore Tests")
    class TransactionStoreTests {

        @Test
        @DisplayName("Records and retrieves authorised transaction")
        void shouldRecordAndRetrieveTransaction() {
            IsoMessage req = createAuthRequest("123456", "4532015588991234", "000000010000");
            IsoMessage resp = messageProcessor.process(req);

            Optional<TransactionRecord> record = transactionStore.find("123456", "453201******1234");
            assertThat(record).isPresent();
            assertThat(record.get().stan()).isEqualTo("123456");
            assertThat(record.get().maskedPan()).isEqualTo("453201******1234");
            assertThat(record.get().authorisedAmount()).isEqualTo("000000010000");
            assertThat(record.get().state()).isEqualTo(TransactionState.AUTHORISED);
            assertThat(record.get().authCode()).isEqualTo(resp.getField(38));
        }

        @Test
        @DisplayName("Updates transaction state atomically")
        void shouldUpdateStateAtomically() {
            IsoMessage req = createAuthRequest("123456", "4532015588991234", "000000010000");
            messageProcessor.process(req);

            String key = "123456:453201******1234";
            Optional<TransactionRecord> updated = transactionStore.updateState(key, TransactionState.REVERSED, "000000010000");

            assertThat(updated).isPresent();
            assertThat(updated.get().state()).isEqualTo(TransactionState.REVERSED);
            assertThat(updated.get().reversedAmount()).isEqualTo("000000010000");
        }
    }

    @Nested
    @DisplayName("0400 Full Reversal Flow")
    class Reversal0400Tests {

        @Test
        @DisplayName("Successfully reverses an existing 0200 authorisation with 0410 (RC 00)")
        void shouldSuccessfullyReverseTransaction() {
            // 1. Authorise 0200
            IsoMessage authReq = createAuthRequest("654321", "4532015588991234", "000000025000");
            IsoMessage authResp = messageProcessor.process(authReq);
            assertThat(authResp.getField(39)).isEqualTo("00");

            // 2. Send 0400 Reversal
            IsoMessage revReq = createReversalRequest("0400", "654321", "4532015588991234", "000000025000");
            IsoMessage revResp = messageProcessor.process(revReq);

            // 3. Verify 0410 Response
            assertThat(revResp.getMti()).isEqualTo("0410");
            assertThat(revResp.getField(39)).isEqualTo("00");
            assertThat(revResp.getField(11)).isEqualTo("654321");
            assertThat(revResp.getField(38)).isEqualTo(authResp.getField(38)); // Echoes original auth code

            // 4. Verify Store State
            Optional<TransactionRecord> stored = transactionStore.find("654321", "453201******1234");
            assertThat(stored).isPresent();
            assertThat(stored.get().state()).isEqualTo(TransactionState.REVERSED);
        }

        @Test
        @DisplayName("Declines duplicate 0400 reversal with RC 94 (Duplicate Transaction)")
        void shouldDeclineDuplicateReversal() {
            // 1. Auth 0200
            IsoMessage authReq = createAuthRequest("777888", "4532015588991234", "000000050000");
            messageProcessor.process(authReq);

            // 2. First 0400 -> Approved
            IsoMessage rev1 = createReversalRequest("0400", "777888", "4532015588991234", "000000050000");
            IsoMessage resp1 = messageProcessor.process(rev1);
            assertThat(resp1.getField(39)).isEqualTo("00");

            // 3. Duplicate 0400 -> Declined with RC 94
            IsoMessage rev2 = createReversalRequest("0400", "777888", "4532015588991234", "000000050000");
            IsoMessage resp2 = messageProcessor.process(rev2);
            assertThat(resp2.getMti()).isEqualTo("0410");
            assertThat(resp2.getField(39)).isEqualTo("94");
        }

        @Test
        @DisplayName("Rejects 0400 for unknown transaction with RC 25 (Unable to Locate Record)")
        void shouldRejectUnknownTransactionReversal() {
            IsoMessage rev = createReversalRequest("0400", "999999", "4532015588999999", "000000010000");
            IsoMessage resp = messageProcessor.process(rev);

            assertThat(resp.getMti()).isEqualTo("0410");
            assertThat(resp.getField(39)).isEqualTo("25");
        }
    }

    @Nested
    @DisplayName("0420 Reversal Advice Flow")
    class Reversal0420Tests {

        @Test
        @DisplayName("Accepts 0420 Reversal Advice with 0430 response (RC 00)")
        void shouldAcceptReversalAdvice() {
            // 1. Auth 0200
            IsoMessage authReq = createAuthRequest("112233", "4532015588991234", "000000015000");
            messageProcessor.process(authReq);

            // 2. Send 0420 Reversal Advice
            IsoMessage adviceReq = createReversalRequest("0420", "112233", "4532015588991234", "000000015000");
            IsoMessage adviceResp = messageProcessor.process(adviceReq);

            assertThat(adviceResp.getMti()).isEqualTo("0430");
            assertThat(adviceResp.getField(39)).isEqualTo("00");

            // 3. Verify Store state is REVERSED
            Optional<TransactionRecord> stored = transactionStore.find("112233", "453201******1234");
            assertThat(stored).isPresent();
            assertThat(stored.get().state()).isEqualTo(TransactionState.REVERSED);
        }

        @Test
        @DisplayName("Unconditionally accepts 0420 even if original transaction is not found (Scheme Rule)")
        void shouldUnconditionallyAcceptAdviceWhenOriginalNotFound() {
            IsoMessage adviceReq = createReversalRequest("0420", "888888", "4532015588998888", "000000015000");
            IsoMessage adviceResp = messageProcessor.process(adviceReq);

            assertThat(adviceResp.getMti()).isEqualTo("0430");
            assertThat(adviceResp.getField(39)).isEqualTo("00");
        }

        @Test
        @DisplayName("Handles Partial Reversal with DE 95 Replacement Amounts")
        void shouldHandlePartialReversalWithDe95() {
            // 1. Auth 0200 for $100.00
            IsoMessage authReq = createAuthRequest("445566", "4532015588991234", "000000010000");
            messageProcessor.process(authReq);

            // 2. 0400 with DE 95 specifying partial reversal of $40.00 (000000004000)
            IsoMessage revReq = createReversalRequest("0400", "445566", "4532015588991234", "000000010000");
            revReq.setField(95, "00000000400000000000600000000000000000000000"); // 12-digit reversed amount

            IsoMessage revResp = messageProcessor.process(revReq);
            assertThat(revResp.getField(39)).isEqualTo("00");

            // 3. Verify state is PARTIALLY_REVERSED
            Optional<TransactionRecord> stored = transactionStore.find("445566", "453201******1234");
            assertThat(stored).isPresent();
            assertThat(stored.get().state()).isEqualTo(TransactionState.PARTIALLY_REVERSED);
            assertThat(stored.get().reversedAmount()).isEqualTo("000000004000");
        }
    }
}
