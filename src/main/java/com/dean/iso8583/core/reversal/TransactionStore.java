package com.dean.iso8583.core.reversal;

import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.utils.IsoMessageSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory Transaction State Store.
 *
 * <h2>Purpose</h2>
 * Maintains a live map of all authorised transactions so that the
 * {@link ReversalEngine} can locate the original {@code 0200} authorisation
 * when a {@code 0400} (Reversal Request) or {@code 0420} (Reversal Advice)
 * arrives.
 *
 * <h2>Keying Strategy</h2>
 * Composite key: {@code STAN (DE 11) + ":" + masked PAN (DE 2)}.
 * This prevents collisions between different cards that share the same STAN
 * in multi-acquirer deployments where STAN counters reset daily.
 *
 * <h2>Enterprise Considerations</h2>
 * <ul>
 *   <li><b>Thread Safety</b>: Uses {@link ConcurrentHashMap} with
 *       {@code compute()} for atomic read-modify-write operations, ensuring
 *       no race condition between a reversal validation and state update.</li>
 *   <li><b>In-Memory Only</b>: Suitable for host simulation and integration
 *       testing. In production, replace with a Redis cluster or a
 *       transactional database with SERIALIZABLE isolation.</li>
 *   <li><b>PCI-DSS</b>: PANs are stored in masked form only. The raw PAN
 *       is masked on ingestion via {@link IsoMessageSanitizer}.</li>
 *   <li><b>Eviction</b>: This implementation has no TTL eviction. Production
 *       systems should apply a 90-day rolling window per card scheme rules.</li>
 * </ul>
 */
@Slf4j
@Component
public class TransactionStore {

    /**
     * Primary store: composite key → TransactionRecord.
     * ConcurrentHashMap provides lock-striping for high-throughput authorisation
     * environments without a global write lock.
     */
    private final ConcurrentHashMap<String, TransactionRecord> store = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Write operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Records a successfully authorised transaction from a {@code 0200} request
     * and its {@code 0210} response.
     *
     * <p>Developer Note: This is called by {@link ReversalEngine} immediately
     * after an approval is issued so that subsequent reversals can always find
     * the original record.</p>
     *
     * @param request  the original 0200 IsoMessage (request)
     * @param response the 0210 IsoMessage (response), used to extract RRN and auth code
     * @return the newly created and stored {@link TransactionRecord}
     */
    public TransactionRecord recordAuthorisation(IsoMessage request, IsoMessage response) {
        String maskedPan = IsoMessageSanitizer.maskPan(request.getField(2));
        String stan      = request.getField(11);

        TransactionRecord record = new TransactionRecord(
                stan,
                maskedPan,
                request.getField(3),
                request.getField(4),
                null,                          // no reversal yet
                request.getField(7),
                response.getField(37),         // RRN from response
                response.getField(38),         // auth code from response
                request.getField(41),
                request.getField(42),
                request.getField(49),
                TransactionState.AUTHORISED,
                Instant.now(),
                Instant.now()
        );

        String key = record.compositeKey();
        store.put(key, record);

        log.info("Transaction recorded — STAN={} PAN={} Amount={} State={}",
                stan, maskedPan, record.authorisedAmount(), record.state());

        return record;
    }

    /**
     * Atomically updates the state of an existing record.
     *
     * <p>Developer Note: Uses {@code ConcurrentHashMap.compute()} to guarantee
     * that the check-and-update is performed without interleaving, preventing a
     * TOCTOU (Time-Of-Check-Time-Of-Use) race condition where two concurrent
     * reversals could both pass the "not yet reversed" check.</p>
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
        TransactionRecord[] updated = new TransactionRecord[1];

        store.compute(key, (k, existing) -> {
            if (existing == null) return null;
            updated[0] = existing.withReversalApplied(newState, reversedAmount);
            return updated[0];
        });

        if (updated[0] != null) {
            log.info("Transaction state updated — Key={} NewState={} ReversedAmount={}",
                    key, newState, reversedAmount);
        }

        return Optional.ofNullable(updated[0]);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Looks up a transaction by its composite key.
     *
     * @param stan      DE 11 — Systems Trace Audit Number
     * @param maskedPan masked PAN (DE 2)
     * @return the stored record, or empty if not found
     */
    public Optional<TransactionRecord> find(String stan, String maskedPan) {
        String key = "%s:%s".formatted(stan, maskedPan);
        return Optional.ofNullable(store.get(key));
    }

    /**
     * Returns an unmodifiable snapshot of all stored transactions.
     * Used by monitoring REST endpoints.
     *
     * <p>Developer Note: Returns the values collection at point-in-time.
     * This is NOT a consistent snapshot — concurrent writes may or may not
     * be reflected depending on timing. For audit exports, prefer a
     * dedicated read replica or a persistent store query.</p>
     *
     * @return unmodifiable collection of all stored records
     */
    public Collection<TransactionRecord> findAll() {
        return Collections.unmodifiableCollection(store.values());
    }

    /**
     * Returns the current number of tracked transactions.
     */
    public int size() {
        return store.size();
    }

    /**
     * Removes a record by composite key. Used for testing and administrative
     * clean-up only — NOT for reversals.
     *
     * @param key composite key
     * @return true if the record was removed
     */
    public boolean remove(String key) {
        return store.remove(key) != null;
    }
}
