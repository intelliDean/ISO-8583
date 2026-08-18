package com.dean.iso8583.core.persistence;

import com.dean.iso8583.core.clearing.ClearingBatch;
import com.dean.iso8583.core.clearing.ClearingRecord;

import java.util.Collection;
import java.util.Optional;

/**
 * Storage repository interface for DMS Batch Clearing and Settlement entities.
 */
public interface ClearingBatchRepository {

    /**
     * Persists an end-of-day clearing batch.
     */
    void saveBatch(ClearingBatch batch);

    /**
     * Finds a clearing batch by its ID.
     */
    Optional<ClearingBatch> findBatchById(String batchId);

    /**
     * Returns all archived clearing batches.
     */
    Collection<ClearingBatch> findAllBatches();

    /**
     * Persists a chargeback dispute record.
     */
    void saveChargeback(ClearingRecord chargeback);

    /**
     * Finds a chargeback record by its ID.
     */
    Optional<ClearingRecord> findChargebackById(String recordId);

    /**
     * Returns all filed chargebacks.
     */
    Collection<ClearingRecord> findAllChargebacks();
}
