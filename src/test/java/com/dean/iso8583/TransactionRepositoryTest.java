package com.dean.iso8583;

import com.dean.iso8583.core.persistence.InMemoryTransactionRepository;
import com.dean.iso8583.core.persistence.TransactionRepository;
import com.dean.iso8583.core.reversal.TransactionRecord;
import com.dean.iso8583.core.reversal.TransactionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TransactionRepository Persistence Tests")
class TransactionRepositoryTest {

    private TransactionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTransactionRepository();
    }

    @Test
    @DisplayName("Should save and retrieve transaction by composite key")
    void testSaveAndFind() {
        TransactionRecord record = new TransactionRecord(
                "000123",
                "453201******1234",
                "000000",
                "000000002550",
                null,
                "0818120000",
                "123456789012",
                "AUTH01",
                "TERM0001",
                "MERCHANT1234567",
                "840",
                TransactionState.AUTHORISED,
                Instant.now(),
                Instant.now()
        );

        repository.save(record);

        Optional<TransactionRecord> found = repository.find("000123", "453201******1234");
        assertTrue(found.isPresent());
        assertEquals("000000002550", found.get().authorisedAmount());
        assertEquals(TransactionState.AUTHORISED, found.get().state());
    }

    @Test
    @DisplayName("Should atomically update transaction state to REVERSED")
    void testUpdateState() {
        TransactionRecord record = new TransactionRecord(
                "000123",
                "453201******1234",
                "000000",
                "000000002550",
                null,
                "0818120000",
                "123456789012",
                "AUTH01",
                "TERM0001",
                "MERCHANT1234567",
                "840",
                TransactionState.AUTHORISED,
                Instant.now(),
                Instant.now()
        );

        repository.save(record);

        Optional<TransactionRecord> updated = repository.updateState(
                record.compositeKey(),
                TransactionState.REVERSED,
                "000000002550"
        );

        assertTrue(updated.isPresent());
        assertEquals(TransactionState.REVERSED, updated.get().state());
        assertEquals("000000002550", updated.get().reversedAmount());
    }
}
