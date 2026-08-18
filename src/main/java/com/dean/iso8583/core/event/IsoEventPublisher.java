package com.dean.iso8583.core.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Event Publisher implementing the Transactional Outbox Pattern for the ISO 8583 Engine.
 *
 * <p>Every domain state change (authorisations, reversals, batch settlements, chargebacks)
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
    public IsoOutboxEvent publish(String aggregateType, String aggregateId, IsoEventType eventType, Object payload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event payload for {}/{}", aggregateType, aggregateId, e);
            payloadJson = "{\"error\":\"serialization_failed\",\"message\":\"" + e.getMessage() + "\"}";
        }

        IsoOutboxEvent event = IsoOutboxEvent.of(aggregateType, aggregateId, eventType, payloadJson);
        outboxRepository.save(event);

        log.info("Domain Event Registered in Outbox: Type={} Aggregate={}:{} EventID={}",
                eventType, aggregateType, aggregateId, event.eventId());

        return event;
    }
}
