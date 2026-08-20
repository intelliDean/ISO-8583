package com.dean.iso8583.core.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Event Publisher implementing the Transactional Outbox Pattern for the ISO 8583 Engine.
 *
 * <p>Every domain state change (authorizations, reversals, batch settlements, chargebacks)
 * is atomically written as an outbox event before dispatching to Kafka topics.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IsoEventPublisher {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Publishes a domain event by persisting it to the transactional outbox.
     *
     * @param aggregateType aggregate name (e.g. "TRANSACTION", "CLEARING_BATCH", "CHARGEBACK")
     * @param aggregateId   unique aggregate ID (e.g. STAN, Batch ID)
     * @param eventType     event classification
     * @param payload       event payload object
     * @return persisted {@link IsoOutboxEvent}
     */
    public IsoOutboxEvent publish(
            String aggregateType,
            String aggregateId,
            IsoEventType eventType,
            Object payload
    ) {
        String payloadJson = serializePayload(payload, aggregateType, aggregateId);
        IsoOutboxEvent event = persistEvent(aggregateType, aggregateId, eventType, payloadJson);
        logPublished(eventType, aggregateType, aggregateId, event);
        return event;
    }

    /**
     * Serializes the payload to JSON, falling back to a synthetic error payload if serialization fails
     * so a bad payload can never prevent the event itself from being recorded in the outbox.
     */
    private String serializePayload(Object payload, String aggregateType, String aggregateId) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event payload for {}/{}", aggregateType, aggregateId, e);
            return buildSerializationFailureJson(e);
        }
    }

    private String buildSerializationFailureJson(JsonProcessingException e) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "error", "serialization_failed",
                    "message", String.valueOf(e.getMessage())
            ));
        } catch (JsonProcessingException fallbackFailure) {
            // Map<String,String> serialization should never fail; last-resort static payload if it somehow does
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    private IsoOutboxEvent persistEvent(
            String aggregateType,
            String aggregateId,
            IsoEventType eventType,
            String payloadJson
    ) {
        IsoOutboxEvent event = IsoOutboxEvent.of(aggregateType, aggregateId, eventType, payloadJson);
        outboxRepository.save(event);
        return event;
    }

    private void logPublished(
            IsoEventType eventType,
            String aggregateType,
            String aggregateId,
            IsoOutboxEvent event
    ) {
        log.info("Domain Event Registered in Outbox: Type={} Aggregate={}:{} EventID={}",
                eventType, aggregateType, aggregateId, event.eventId());
    }
}