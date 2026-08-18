package com.dean.iso8583.core.event;

import java.util.List;
import java.util.Optional;

/**
 * Storage abstraction for Transactional Outbox events.
 */
public interface OutboxEventRepository {

    /**
     * Persists a newly created outbox event.
     */
    void save(IsoOutboxEvent event);

    /**
     * Finds all pending events awaiting publication to Kafka.
     *
     * @param limit maximum number of events to fetch
     * @return list of pending events ordered by creation time
     */
    List<IsoOutboxEvent> findPendingEvents(int limit);

    /**
     * Updates an existing event (e.g. marking published or failed).
     */
    void update(IsoOutboxEvent event);

    /**
     * Finds an event by its unique ID.
     */
    Optional<IsoOutboxEvent> findById(String eventId);

    /**
     * Total number of events currently stored.
     */
    int size();

    /**
     * Number of events currently in PENDING state.
     */
    int countPending();
}
