package com.dean.iso8583.core.persistence;

import com.dean.iso8583.core.clearing.ClearingBatch;
import com.dean.iso8583.core.clearing.ClearingRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory implementation of {@link ClearingBatchRepository}.
 */
@Slf4j
@Repository
public class InMemoryClearingBatchRepository implements ClearingBatchRepository {

    private final ConcurrentHashMap<String, ClearingBatch> batchArchive = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClearingRecord> chargebackStore = new ConcurrentHashMap<>();

    @Override
    public void saveBatch(ClearingBatch batch) {
        batchArchive.put(batch.batchId(), batch);
        log.debug("Clearing batch stored: ID={} Records={}", batch.batchId(), batch.totalTransactions());
    }

    @Override
    public Optional<ClearingBatch> findBatchById(String batchId) {
        return Optional.ofNullable(batchArchive.get(batchId));
    }

    @Override
    public Collection<ClearingBatch> findAllBatches() {
        return Collections.unmodifiableCollection(batchArchive.values());
    }

    @Override
    public void saveChargeback(ClearingRecord chargeback) {
        chargebackStore.put(chargeback.recordId(), chargeback);
        log.debug("Chargeback stored: ID={} STAN={}", chargeback.recordId(), chargeback.stan());
    }

    @Override
    public Optional<ClearingRecord> findChargebackById(String recordId) {
        return Optional.ofNullable(chargebackStore.get(recordId));
    }

    @Override
    public Collection<ClearingRecord> findAllChargebacks() {
        return Collections.unmodifiableCollection(chargebackStore.values());
    }
}
