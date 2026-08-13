package com.dean.iso8583.core.echo;

import java.time.Instant;

/**
 * Developer Note:
 * Real-time channel telemetry and heartbeat statistics report.
 *
 * @param status              current health evaluation of the channel
 * @param totalEchoesSent     cumulative count of 0800 echo messages dispatched
 * @param successfulEchoes    cumulative count of successfully acknowledged 0810 responses
 * @param failedEchoes        cumulative count of failed / timed-out echo attempts
 * @param consecutiveFailures current count of unacknowledged consecutive echo attempts
 * @param lastLatencyMs       most recent echo roundtrip network latency in milliseconds
 * @param lastEchoTime        timestamp of the most recent echo attempt
 * @param lastSuccessTime     timestamp of the most recent successful echo acknowledgment
 * @param lastError           last recorded error message (or null if healthy)
 * @param schedulerEnabled    whether the automatic background echo cron/scheduler is active
 * @param intervalSeconds     interval between automatic background echo checks in seconds
 */
public record ChannelStatusReport(
        ChannelHealthStatus status,
        long totalEchoesSent,
        long successfulEchoes,
        long failedEchoes,
        int consecutiveFailures,
        Long lastLatencyMs,
        Instant lastEchoTime,
        Instant lastSuccessTime,
        String lastError,
        boolean schedulerEnabled,
        long intervalSeconds
) {
}
