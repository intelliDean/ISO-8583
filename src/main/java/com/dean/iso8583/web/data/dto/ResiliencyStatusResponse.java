package com.dean.iso8583.web.data.dto;

import java.util.List;

/**
 * Telemetry response containing distributed persistence, state locking,
 * and Kafka event streaming status.
 */
public record ResiliencyStatusResponse(
        String persistenceEngine,
        int totalTransactions,
        int totalClearingBatches,
        int totalChargebacks,
        String lockEngine,
        String outboxStatus,
        int pendingOutboxEvents,
        long totalEventsDispatched,
        List<com.dean.iso8583.core.event.IsoOutboxEvent> recentEvents
) {}
