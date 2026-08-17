package com.dean.iso8583.core.echo.enums;

/**
 * Developer Note:
 * Represents the health status of an ISO 8583 communication channel based on
 * real-time keep-alive echo results.
 *
 * <h2>Health Evaluation States</h2>
 * <ul>
 *   <li><b>{@link #HEALTHY}</b> — Consecutive successful echo responses received with acceptable roundtrip latency.</li>
 *   <li><b>{@link #DEGRADED}</b> — Occasional missed echos or high latency, but below the failure threshold.</li>
 *   <li><b>{@link #DOWN}</b> — Consecutive echo failures have met or exceeded the threshold. The channel is considered dead.</li>
 *   <li><b>{@link #UNKNOWN}</b> — No echo message has been transmitted yet (initial startup state).</li>
 * </ul>
 */
public enum ChannelHealthStatus {

    /** All recent echo messages acknowledged successfully within SLA. */
    HEALTHY,

    /** Intermittent latency or sporadic packet drops detected. */
    DEGRADED,

    /** Peer host is unreachable or failing echo health checks beyond threshold. */
    DOWN,

    /** Initial state before the first echo handshake is executed. */
    UNKNOWN
}
