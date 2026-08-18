package com.dean.iso8583.core.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional Outbox Event Envelope.
 *
 * <p>Implements the Transactional Outbox Pattern to guarantee at-least-once message
 * delivery to Apache Kafka without distributed dual-write inconsistency.</p>
 *
 * @param eventId       unique UUID identifying this event instance
 * @param aggregateType aggregate classification (e.g., "TRANSACTION", "CLEARING_BATCH", "CHARGEBACK")
 * @param aggregateId   domain identifier (e.g. STAN, Batch ID, or Key ID)
 * @param eventType     specific domain event type
 * @param payloadJson   serialized JSON string of the domain payload
 * @param status        outbox delivery status (PENDING, PUBLISHED, FAILED)
 * @param retryCount    number of failed publication attempts
 * @param createdAt     timestamp when the event was recorded
 * @param publishedAt   timestamp when Kafka acknowledged publication (null if pending)
 */
public record IsoOutboxEvent(
        String eventId,
        String aggregateType,
        String aggregateId,
        IsoEventType eventType,
        String payloadJson,
        OutboxStatus status,
        int retryCount,
        Instant createdAt,
        Instant publishedAt
) {

    public enum OutboxStatus {
        PENDING,
        PUBLISHED,
        FAILED
    }

    /**
     * Factory helper to create a fresh pending outbox event.
     */
    public static IsoOutboxEvent of(String aggregateType, String aggregateId, IsoEventType eventType, String payloadJson) {
        return new IsoOutboxEvent(
                UUID.randomUUID().toString(),
                aggregateType,
                aggregateId,
                eventType,
                payloadJson,
                OutboxStatus.PENDING,
                0,
                Instant.now(),
                null
        );
    }

    /**
     * Returns a copy marked as successfully published.
     */
    public IsoOutboxEvent markPublished() {
        return new IsoOutboxEvent(
                eventId,
                aggregateType,
                aggregateId,
                eventType,
                payloadJson,
                OutboxStatus.PUBLISHED,
                retryCount,
                createdAt,
                Instant.now()
        );
    }

    /**
     * Returns a copy with incremented retry count and updated status.
     */
    public IsoOutboxEvent markFailed() {
        return new IsoOutboxEvent(
                eventId,
                aggregateType,
                aggregateId,
                eventType,
                payloadJson,
                retryCount >= 5 ? OutboxStatus.FAILED : OutboxStatus.PENDING,
                retryCount + 1,
                createdAt,
                null
        );
    }
}
