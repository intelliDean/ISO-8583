package com.dean.iso8583.core.persistence;

import com.dean.iso8583.core.reversal.TransactionRecord;
import com.dean.iso8583.core.reversal.TransactionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory implementation of {@link TransactionRepository}.
 */
@Slf4j
@Repository
public class InMemoryTransactionRepository implements TransactionRepository {

    private final ConcurrentHashMap<String, TransactionRecord> store = new ConcurrentHashMap<>();

    @Override
    public void save(TransactionRecord record) {
        String key = record.compositeKey();
        store.put(key, record);
        log.debug("Transaction persisted in memory: Key='{}' State='{}'", key, record.state());
    }

    @Override
    public Optional<TransactionRecord> find(String stan, String maskedPan) {
        String key = "%s:%s".formatted(stan, maskedPan);
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public Optional<TransactionRecord> findByKey(String compositeKey) {
        return Optional.ofNullable(store.get(compositeKey));
    }

    @Override
    public Optional<TransactionRecord> updateState(String compositeKey, TransactionState newState, String reversedAmount) {
        TransactionRecord[] updated = new TransactionRecord[1];
        store.compute(compositeKey, (k, existing) -> {
            if (existing == null) return null;
            updated[0] = existing.withReversalApplied(newState, reversedAmount);
            return updated[0];
        });
        return Optional.ofNullable(updated[0]);
    }

    @Override
    public Collection<TransactionRecord> findAll() {
        return Collections.unmodifiableCollection(store.values());
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public boolean delete(String compositeKey) {
        return store.remove(compositeKey) != null;
    }
}
