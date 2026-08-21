package com.dean.iso8583.core.echo;

import com.dean.iso8583.core.IsoPacker;
import com.dean.iso8583.core.dto.IsoMessage;
import com.dean.iso8583.core.echo.dto.ChannelStatusReport;
import com.dean.iso8583.core.echo.dto.EchoResult;
import com.dean.iso8583.core.echo.dto.IsoEchoProperties;
import com.dean.iso8583.core.echo.enums.ChannelHealthStatus;
import com.dean.iso8583.core.echo.enums.NetworkManagementCode;
import com.dean.iso8583.core.metrics.IsoMetrics;
import com.dean.iso8583.web.data.dto.WebDTOs;
import com.dean.iso8583.web.data.utils.IsoTcpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Enterprise Automatic Keep-Alive Echo Manager & Scheduler.
 *
 * <h2>Purpose & Enterprise Context</h2>
 * In enterprise payment systems (e.g. Visa BASE I/II, Mastercard Banknet/MIP, AS 2805),
 * point-to-point TCP connections between acquirers, switches, and issuer hosts must be kept
 * active and verified continuously.
 *
 * <ul>
 *   <li><b>Dead-Peer Detection</b>: Silent socket disconnects (cable cuts, firewall state table drops)
 *       are detected immediately rather than failing customer transactions at the point of sale.</li>
 *   <li><b>Heartbeat Format</b>: Dispatches {@code 0800} Network Management Requests with
 *       DE 70 set to {@code 301} (Echo Test) and expects an approved {@code 0810} response (DE 39 = "00").</li>
 *   <li><b>Automated Health Degradation</b>: Maintains rolling telemetry and automatically transitions
 *       channel status between {@link ChannelHealthStatus#HEALTHY}, {@link ChannelHealthStatus#DEGRADED},
 *       and {@link ChannelHealthStatus#DOWN} based on consecutive failure thresholds.</li>
 * </ul>
 */
@Slf4j
@Component
public class IsoEchoManager {

    // ── Injected dependencies ──────────────────────────────────────────────────

    private final IsoEchoProperties properties;
    private final IsoTcpClient tcpClient;
    private final IsoMetrics isoMetrics;

    // ── Rolling telemetry counters ─────────────────────────────────────────────

    private final AtomicInteger stanGenerator    = new AtomicInteger(1);
    private final AtomicLong totalEchoesSent     = new AtomicLong(0);
    private final AtomicLong successfulEchoes    = new AtomicLong(0);
    private final AtomicLong failedEchoes        = new AtomicLong(0);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    private final ReentrantLock statusLock = new ReentrantLock();

    // ── Volatile state (written under statusLock, read without it for speed) ───

    /**
     * <b>volatile</b>: this variable may be accessed by multiple threads, and whenever one thread changes it, other threads must see that change.
     */
    private volatile ChannelHealthStatus healthStatus = ChannelHealthStatus.UNKNOWN;
    private volatile Long    lastLatencyMs   = null;
    private volatile Instant lastEchoTime    = null;
    private volatile Instant lastSuccessTime = null;
    private volatile String  lastError       = null;

    private static final DateTimeFormatter DE7_FORMATTER =
            DateTimeFormatter.ofPattern("MMddHHmmss").withZone(ZoneOffset.UTC);

    @Autowired
    public IsoEchoManager(
            IsoEchoProperties properties,
            IsoTcpClient tcpClient,
            @Autowired(required = false) IsoMetrics isoMetrics
    ) {
        this.properties = properties;
        this.tcpClient  = tcpClient;
        this.isoMetrics = isoMetrics;
    }


    /**
     * <b>triggerEcho</b>: builds and transmits an on-demand ISO 8583 {@code 0800} Echo Test request message,
     * awaiting and validating the {@code 0810} response.
     *
     * @return structured {@link EchoResult} with latency, response codes, and telemetry
     */
    public EchoResult triggerEcho() {
        String stan = generateStan();
        String transmissionDateTime = DE7_FORMATTER.format(Instant.now());

        IsoMessage echoMessage = buildEchoMessage(stan, transmissionDateTime);
        String packedRequest = IsoPacker.packToString(echoMessage);

        totalEchoesSent.incrementAndGet();
        lastEchoTime = Instant.now();

        log.debug("Sending ISO 8583 0800 Keep-Alive Echo — STAN={} DE70={}",
                stan, NetworkManagementCode.ECHO_TEST.getCode());

        WebDTOs.SimulateResult simulation = tcpClient.simulate(packedRequest);

        if (simulation.success() && "00".equals(simulation.responseCode()) && "0810".equals(simulation.responseMti())) {
            return handleSuccessfulEcho(stan, transmissionDateTime, packedRequest, simulation);
        } else {
            return handleFailedEcho(stan, transmissionDateTime, packedRequest, simulation);
        }
    }

    /**
     * <b>scheduledEcho</b>: periodic scheduled heartbeat task.
     * Runs according to the configured interval when {@code iso.echo.enabled=true}.
     */
    @Scheduled(fixedDelayString = "${iso.echo.interval-seconds:30}000")
    public void scheduledEcho() {
        if (!properties.enabled()) return;

        try {
            log.info("Executing scheduled ISO 8583 keep-alive echo...");
            EchoResult result = triggerEcho();
            log.info("Scheduled keep-alive echo completed — status={} latency={}ms",
                    result.success() ? "SUCCESS" : "FAILURE", result.roundtripMs());
        } catch (Exception ex) {
            log.error("Scheduled ISO 8583 echo encountered an unexpected error", ex);
        }
    }

    /**
     * <b>getChannelStatus</b>: returns an immutable snapshot report of the current channel telemetry and health status.
     */
    public ChannelStatusReport getChannelStatus() {
        return new ChannelStatusReport(
                healthStatus,
                totalEchoesSent.get(),
                successfulEchoes.get(),
                failedEchoes.get(),
                consecutiveFailures.get(),
                lastLatencyMs,
                lastEchoTime,
                lastSuccessTime,
                lastError,
                properties.enabled(),
                properties.intervalSeconds()
        );
    }

    private EchoResult handleSuccessfulEcho(
            String stan,
            String transmissionDateTime,
            String packedRequest,
            WebDTOs.SimulateResult simulation
    ) {
        successfulEchoes.incrementAndGet();
        consecutiveFailures.set(0);

        statusLock.lock();
        try {
            lastLatencyMs = simulation.roundtripMs();
            lastSuccessTime = Instant.now();
            lastError = null;
            healthStatus = ChannelHealthStatus.HEALTHY;
        } finally {
            statusLock.unlock();
        }

        log.info("ISO 8583 0800 Echo acknowledged (0810/00) — STAN={} Latency={}ms", stan, simulation.roundtripMs());

        if (isoMetrics != null) {
            isoMetrics.recordEcho(true, simulation.roundtripMs());
        }

        return EchoResult.success(
                EchoResult.SuccessRequest.builder()
                        .roundtripMs(simulation.roundtripMs())
                        .stan(stan)
                        .transmissionDateTime(transmissionDateTime)
                        .responseMti(simulation.responseMti())
                        .responseCode(simulation.responseCode())
                        .rawRequest(packedRequest)
                        .rawResponse(simulation.responsePayload())
                        .build()
        );
    }

    private EchoResult handleFailedEcho(
            String stan,
            String transmissionDateTime,
            String packedRequest,
            WebDTOs.SimulateResult simulation
    ) {
        failedEchoes.incrementAndGet();
        int failures = consecutiveFailures.incrementAndGet();

        String errorMsg = simulation.message() != null ? simulation.message() : "Echo response rejected or timed out";

        statusLock.lock();
        try {
            lastLatencyMs = simulation.roundtripMs();
            lastError = errorMsg;
            if (failures >= properties.failureThreshold()) {
                healthStatus = ChannelHealthStatus.DOWN;
                log.warn("ISO 8583 communication channel marked DOWN after {} consecutive failures", failures);
            } else {
                healthStatus = ChannelHealthStatus.DEGRADED;
            }
        } finally {
            statusLock.unlock();
        }

        log.warn("ISO 8583 0800 Echo failed — STAN={} Failures={} Error={}", stan, failures, errorMsg);

        if (isoMetrics != null) {
            isoMetrics.recordEcho(false, simulation.roundtripMs());
        }

        return EchoResult.failure(EchoResult.FailureRequest.builder()
                .roundtripMs(simulation.roundtripMs())
                .stan(stan)
                .transmissionDateTime(transmissionDateTime)
                .rawRequest(packedRequest)
                .errorMessage(errorMsg)
                .build()
        );
    }

    private IsoMessage buildEchoMessage(String stan, String transmissionDateTime) {
        IsoMessage message = new IsoMessage("0800");
        message.setHeader(properties.tpduHeader());
        message.setField(7, transmissionDateTime);
        message.setField(11, stan);
        message.setField(70, NetworkManagementCode.ECHO_TEST.getCode());
        return message;
    }

    private String generateStan() {
        int stan = stanGenerator.getAndUpdate(val -> (val >= 999999) ? 1 : val + 1);
        return String.format("%06d", stan);
    }
}
