package com.dean.iso8583;

import com.dean.iso8583.core.event.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Transactional Outbox Pattern & Event Streaming Tests")
class OutboxEventStreamingTest {

    private OutboxEventRepository outboxRepository;
    private IsoEventPublisher eventPublisher;
    private OutboxPollerService pollerService;

    @BeforeEach
    void setUp() {
        outboxRepository = new InMemoryOutboxEventRepository();
        eventPublisher = new IsoEventPublisher(outboxRepository, new ObjectMapper());
        pollerService = new OutboxPollerService(outboxRepository);
    }

    @Test
    @DisplayName("Should publish domain events to Outbox repository in PENDING state")
    void testPublishToOutbox() {
        Map<String, Object> payload = Map.of(
                "stan", "000123",
                "amount", "000000002550",
                "maskedPan", "453201******1234"
        );

        IsoOutboxEvent event = eventPublisher.publish(
                "TRANSACTION",
                "000123",
                IsoEventType.TRANSACTION_AUTHORISED,
                payload
        );

        assertNotNull(event);
        assertNotNull(event.eventId());
        assertEquals("TRANSACTION", event.aggregateType());
        assertEquals("000123", event.aggregateId());
        assertEquals(IsoEventType.TRANSACTION_AUTHORISED, event.eventType());
        assertEquals(IsoOutboxEvent.OutboxStatus.PENDING, event.status());

        assertEquals(1, outboxRepository.size());
        assertEquals(1, outboxRepository.countPending());
    }

    @Test
    @DisplayName("Should sweep pending outbox events and mark them PUBLISHED during polling cycle")
    void testOutboxPollingAndStreaming() {
        // Enqueue 3 events
        eventPublisher.publish("TRANSACTION", "000101", IsoEventType.TRANSACTION_AUTHORISED, Map.of("stan", "000101"));
        eventPublisher.publish("TRANSACTION", "000102", IsoEventType.TRANSACTION_REVERSED,   Map.of("stan", "000102"));
        eventPublisher.publish("CLEARING_BATCH", "BATCH-001", IsoEventType.CLEARING_BATCH_GENERATED, Map.of("batchId", "BATCH-001"));

        assertEquals(3, outboxRepository.countPending());

        // Execute background poller
        pollerService.pollAndDispatch();

        // All should be marked PUBLISHED
        assertEquals(0, outboxRepository.countPending(), "Pending count should be 0 after successful dispatch");
        assertEquals(3, pollerService.getTotalDispatched(), "Poller should have dispatched 3 events");

        List<IsoOutboxEvent> pending = outboxRepository.findPendingEvents(10);
        assertTrue(pending.isEmpty());
    }
}
