package com.dean.iso8583.core.echo.dto;

import com.dean.iso8583.core.clearing.utils.ClearingUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

import static com.dean.iso8583.core.clearing.utils.ClearingUtils.TPDU;

/**
 * Developer Note:
 * Configuration properties for the ISO 8583 Automatic Keep-Alive Echo Scheduler.
 *
 * @param enabled          whether automatic background scheduling is active
 * @param intervalSeconds  interval in seconds between successive heartbeat echo requests (default: 30)
 * @param failureThreshold number of consecutive missed echos before channel is declared DOWN (default: 3)
 * @param timeoutMs        socket read/connect timeout in milliseconds for each echo request (default: 5000)
 * @param tpduHeader       TPDU routing header to prepend to 0800 messages (default: "6000000000")
 */
@ConfigurationProperties(prefix = "iso.echo")
public record IsoEchoProperties(
        boolean enabled,
        int intervalSeconds,
        int failureThreshold,
        int timeoutMs,
        String tpduHeader
) {
    public IsoEchoProperties {
        if (intervalSeconds <= 0) {
            intervalSeconds = 30;
        }
        if (failureThreshold <= 0) {
            failureThreshold = 3;
        }
        if (timeoutMs <= 0) {
            timeoutMs = 5000;
        }
        if (tpduHeader == null || tpduHeader.isBlank()) {
            tpduHeader = TPDU;
        }
    }
}
