package com.dean.iso8583.core.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Thread-safe in-memory implementation of {@link OutboxEventRepository}.
 *
 * <p>Acts as default store for standalone execution, local development, and unit testing,
 * ensuring high throughput without mandatory external database dependencies.</p>
 */
@Slf4j
@Repository
public class InMemoryOutboxEventRepository implements OutboxEventRepository {

    private final ConcurrentHashMap<String, IsoOutboxEvent> store = new ConcurrentHashMap<>();

    @Override
    public void save(IsoOutboxEvent event) {
        storeAndLog(event, "Outbox event saved: ID={} Type={} Aggregate={}:{}",
                event.eventId(), event.eventType(), event.aggregateType(), event.aggregateId());
    }

    @Override
    public void update(IsoOutboxEvent event) {
        storeAndLog(event, "Outbox event updated: ID={} Status={}", event.eventId(), event.status());
    }

    private void storeAndLog(IsoOutboxEvent event, String logTemplate, Object... logArgs) {
        store.put(event.eventId(), event);
        log.debug(logTemplate, logArgs);
    }

    @Override
    public List<IsoOutboxEvent> findPendingEvents(int limit) {
        return pendingEvents()
                .sorted(Comparator.comparing(IsoOutboxEvent::createdAt))
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<IsoOutboxEvent> findById(String eventId) {
        return Optional.ofNullable(store.get(eventId));
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public int countPending() {
        return (int) pendingEvents().count();
    }

    private Stream<IsoOutboxEvent> pendingEvents() {
        return store.values().stream()
                .filter(e -> e.status() == IsoOutboxEvent.OutboxStatus.PENDING);
    }
}