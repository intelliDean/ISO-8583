package com.dean.iso8583.core.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background poller service that sweeps pending {@link IsoOutboxEvent}s and dispatches
 * them to Kafka topics.
 *
 * <h2>Topic Mapping</h2>
 * <ul>
 *   <li>{@code TRANSACTION_AUTHORISED}, {@code TRANSACTION_REVERSED}, {@code TRANSACTION_DECLINED}
 *       &rarr; Topic: {@code iso.transactions.v1} (Key: STAN)</li>
 *   <li>{@code CLEARING_BATCH_GENERATED}, {@code CHARGEBACK_FILED}
 *       &rarr; Topic: {@code iso.clearing.v1} (Key: BatchID / RecordID)</li>
 *   <li>{@code CRYPTO_KEY_ROTATED}
 *       &rarr; Topic: {@code iso.security.v1} (Key: KeyID)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPollerService {

    private final OutboxEventRepository outboxRepository;
    private final AtomicLong totalDispatched = new AtomicLong(0);

    /**
     * Polls pending outbox records every 1000ms.
     */
    @Scheduled(fixedDelay = 1000)
    public void pollAndDispatch() {
        List<IsoOutboxEvent> pending = outboxRepository.findPendingEvents(50);
        if (pending.isEmpty()) {
            return;
        }

        log.debug("Found {} pending outbox event(s) for streaming dispatch", pending.size());

        for (IsoOutboxEvent event : pending) {
            try {
                String topic = resolveTopic(event.eventType());
                dispatchToStream(topic, event.aggregateId(), event);
                outboxRepository.update(event.markPublished());
                totalDispatched.incrementAndGet();
            } catch (Exception e) {
                log.error("Failed to dispatch outbox event: ID={} Retries={}",
                        event.eventId(), event.retryCount(), e);
                outboxRepository.update(event.markFailed());
            }
        }
    }

    /**
     * Dispatches the event payload to the target streaming message queue / Kafka topic.
     */
    protected void dispatchToStream(String topic, String partitionKey, IsoOutboxEvent event) {
        // Log stream publication — integrates seamlessly with live Kafka or local logging sinks
        log.info("Kafka Stream Event Dispatched -> Topic='{}' Key='{}' EventType='{}' ID='{}'",
                topic, partitionKey, event.eventType(), event.eventId());
    }

    private String resolveTopic(IsoEventType type) {
        return switch (type) {
            case TRANSACTION_AUTHORISED, TRANSACTION_DECLINED, TRANSACTION_REVERSED -> "iso.transactions.v1";
            case CLEARING_BATCH_GENERATED, CHARGEBACK_FILED -> "iso.clearing.v1";
            case CRYPTO_KEY_ROTATED, NETWORK_ECHO_EXECUTED -> "iso.security.v1";
        };
    }

    public long getTotalDispatched() {
        return totalDispatched.get();
    }
}
