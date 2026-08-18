package com.dean.iso8583.core.reversal;

import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.event.IsoEventPublisher;
import com.dean.iso8583.core.event.IsoEventType;
import com.dean.iso8583.core.lock.DistributedLockService;
import com.dean.iso8583.core.persistence.TransactionRepository;
import com.dean.iso8583.core.utils.IsoMessageSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * Enterprise Transaction State Store with Distributed Locking &amp; Outbox Event Streaming.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Maintains state transitions (AUTHORISED &rarr; REVERSED / PARTIALLY_REVERSED / CLEARED).</li>
 *   <li>Coordinates cluster-wide concurrency using {@link DistributedLockService}.</li>
 *   <li>Persists audit state via {@link TransactionRepository}.</li>
 *   <li>Emits domain events to the Transactional Outbox via {@link IsoEventPublisher}.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionStore {

    private final TransactionRepository transactionRepository;
    private final DistributedLockService lockService;
    private final IsoEventPublisher eventPublisher;

    private static final long LOCK_WAIT_MS = 3000;
    private static final long LOCK_LEASE_MS = 5000;

    /**
     * Records a successfully authorised transaction from a {@code 0200} request
     * and its {@code 0210} response under a distributed lock.
     *
     * @param request  the original 0200 IsoMessage (request)
     * @param response the 0210 IsoMessage (response), used to extract RRN and auth code
     */
    public void recordAuthorisation(IsoMessage request, IsoMessage response) {
        String maskedPan = IsoMessageSanitizer.maskPan(request.getField(2));
        String stan      = request.getField(11);
        String lockKey   = "lock:stan:%s:pan:%s".formatted(stan, maskedPan);

        lockService.executeWithLock(lockKey, LOCK_WAIT_MS, LOCK_LEASE_MS, () -> {
            TransactionRecord record = new TransactionRecord(
                    stan,
                    maskedPan,
                    request.getField(3),
                    request.getField(4),
                    null,
                    request.getField(7),
                    response.getField(37),
                    response.getField(38),
                    request.getField(41),
                    request.getField(42),
                    request.getField(49),
                    TransactionState.AUTHORISED,
                    Instant.now(),
                    Instant.now()
            );

            transactionRepository.save(record);

            log.info("Transaction recorded — STAN={} PAN={} Amount={} State={}",
                    stan, maskedPan, record.authorisedAmount(), record.state());

            // Emit domain event for Kafka Outbox streaming
            eventPublisher.publish("TRANSACTION", stan, IsoEventType.TRANSACTION_AUTHORISED, record);
            return record;
        });
    }

    /**
     * Atomically updates the state of an existing record under distributed lock.
     *
     * @param key            composite key (STAN:maskedPan)
     * @param newState       target state
     * @param reversedAmount total amount reversed
     * @return updated record, or empty if the key does not exist
     */
    public Optional<TransactionRecord> updateState(
            String key,
            TransactionState newState,
            String reversedAmount
    ) {
        String lockKey = "lock:txn:" + key;

        return lockService.executeWithLock(lockKey, LOCK_WAIT_MS, LOCK_LEASE_MS, () -> {
            Optional<TransactionRecord> updated = transactionRepository.updateState(key, newState, reversedAmount);

            updated.ifPresent(record -> {
                log.info("Transaction state updated — Key={} NewState={} ReversedAmount={}",
                        key, newState, reversedAmount);

                IsoEventType eventType = (newState == TransactionState.REVERSED || newState == TransactionState.PARTIALLY_REVERSED)
                        ? IsoEventType.TRANSACTION_REVERSED
                        : IsoEventType.TRANSACTION_AUTHORISED;

                eventPublisher.publish("TRANSACTION", record.stan(), eventType, record);
            });

            return updated;
        });
    }

    /**
     * Looks up a transaction by its composite key.
     */
    public Optional<TransactionRecord> find(String stan, String maskedPan) {
        return transactionRepository.find(stan, maskedPan);
    }

    /**
     * Returns a snapshot of all stored transactions.
     */
    public Collection<TransactionRecord> findAll() {
        return transactionRepository.findAll();
    }

    /**
     * Returns the total count of tracked transactions.
     */
    public int size() {
        return transactionRepository.size();
    }

    /**
     * Removes a record by composite key (for testing / admin clean-up).
     */
    public boolean remove(String key) {
        return transactionRepository.delete(key);
    }
}
