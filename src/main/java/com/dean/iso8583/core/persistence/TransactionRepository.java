package com.dean.iso8583.core.persistence;

import com.dean.iso8583.core.reversal.TransactionRecord;
import com.dean.iso8583.core.reversal.TransactionState;

import java.util.Collection;
import java.util.Optional;

/**
 * Storage repository interface for ISO 8583 financial transactions.
 *
 * <p>Provides durable storage abstraction for {@link TransactionRecord} supporting
 * PostgreSQL in cluster deployments and in-memory storage for standalone/test modes.</p>
 */
public interface TransactionRepository {

    /**
     * Persists or updates a transaction record.
     */
    void save(TransactionRecord record);

    /**
     * Looks up a transaction by composite key (STAN + masked PAN).
     */
    Optional<TransactionRecord> find(String stan, String maskedPan);

    /**
     * Looks up a transaction by raw composite key string.
     */
    Optional<TransactionRecord> findByKey(String compositeKey);

    /**
     * Atomically updates the state of an existing transaction record.
     */
    Optional<TransactionRecord> updateState(String compositeKey, TransactionState newState, String reversedAmount);

    /**
     * Returns all stored transactions.
     */
    Collection<TransactionRecord> findAll();

    /**
     * Returns the total count of stored transactions.
     */
    int size();

    /**
     * Deletes a transaction by composite key.
     */
    boolean delete(String compositeKey);
}
