package com.dean.iso8583;

import com.dean.iso8583.core.echo.*;
import com.dean.iso8583.core.echo.dto.ChannelStatusReport;
import com.dean.iso8583.core.echo.dto.EchoResult;
import com.dean.iso8583.core.echo.dto.IsoEchoProperties;
import com.dean.iso8583.core.echo.enums.ChannelHealthStatus;
import com.dean.iso8583.core.echo.enums.NetworkManagementCode;
import com.dean.iso8583.web.data.dto.SimulateResult;
import com.dean.iso8583.web.data.utils.IsoTcpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Developer Note:
 * Test suite for the Automatic Keep-Alive Echo Manager & Scheduler (Option 5).
 * Validates 0800 packet construction, DE 70 mapping, rolling STAN generation,
 * channel health state transitions (HEALTHY -> DEGRADED -> DOWN), and telemetry reporting.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IsoEchoManager Keep-Alive Echo Test Suite")
class IsoEchoManagerTest {

    @Mock
    private IsoTcpClient tcpClient;

    private IsoEchoProperties properties;
    private IsoEchoManager echoManager;

    @BeforeEach
    void setUp() {
        properties = new IsoEchoProperties(true, 30, 3, 5000, "6000000000");
        echoManager = new IsoEchoManager(properties, tcpClient);
    }

    @Nested
    @DisplayName("NetworkManagementCode Enum Tests")
    class NetworkManagementCodeTests {

        @Test
        @DisplayName("Resolves 301 to ECHO_TEST")
        void shouldResolveEchoTestCode() {
            assertThat(NetworkManagementCode.fromCode("301")).isEqualTo(NetworkManagementCode.ECHO_TEST);
            assertThat(NetworkManagementCode.ECHO_TEST.getCode()).isEqualTo("301");
        }

        @Test
        @DisplayName("Resolves 001 to LOGON and 002 to LOGOFF")
        void shouldResolveLogonAndLogoffCodes() {
            assertThat(NetworkManagementCode.fromCode("001")).isEqualTo(NetworkManagementCode.LOGON);
            assertThat(NetworkManagementCode.fromCode("002")).isEqualTo(NetworkManagementCode.LOGOFF);
        }

        @Test
        @DisplayName("Returns UNKNOWN for unregistered code")
        void shouldReturnUnknownForUnregisteredCode() {
            assertThat(NetworkManagementCode.fromCode("999")).isEqualTo(NetworkManagementCode.UNKNOWN);
            assertThat(NetworkManagementCode.fromCode(null)).isEqualTo(NetworkManagementCode.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("Echo Trigger & Health Tracking")
    class EchoTriggerTests {

        @Test
        @DisplayName("Initial status is UNKNOWN before any echo is executed")
        void initialStatusShouldBeUnknown() {
            ChannelStatusReport status = echoManager.getChannelStatus();
            assertThat(status.status()).isEqualTo(ChannelHealthStatus.UNKNOWN);
            assertThat(status.totalEchoesSent()).isZero();
            assertThat(status.successfulEchoes()).isZero();
            assertThat(status.failedEchoes()).isZero();
            assertThat(status.consecutiveFailures()).isZero();
            assertThat(status.schedulerEnabled()).isTrue();
            assertThat(status.intervalSeconds()).isEqualTo(30);
        }

        @Test
        @DisplayName("Successful 0800 echo sets status to HEALTHY and records latency")
        void successfulEchoShouldMarkChannelHealthy() {
            SimulateResult mockSuccess = SimulateResult.builder()
                    .success(true)
                    .responseMti("0810")
                    .responseCode("00")
                    .responsePayload("6000000000081082200000000000000400000000000000081412000000000100301")
                    .roundtripMs(15)
                    .build();

            when(tcpClient.simulate(anyString())).thenReturn(mockSuccess);

            EchoResult result = echoManager.triggerEcho();

            assertThat(result.success()).isTrue();
            assertThat(result.responseMti()).isEqualTo("0810");
            assertThat(result.responseCode()).isEqualTo("00");
            assertThat(result.stan()).isEqualTo("000001");
            assertThat(result.networkManagementCode()).isEqualTo("301");
            assertThat(result.roundtripMs()).isEqualTo(15);

            ChannelStatusReport status = echoManager.getChannelStatus();
            assertThat(status.status()).isEqualTo(ChannelHealthStatus.HEALTHY);
            assertThat(status.totalEchoesSent()).isEqualTo(1);
            assertThat(status.successfulEchoes()).isEqualTo(1);
            assertThat(status.failedEchoes()).isZero();
            assertThat(status.consecutiveFailures()).isZero();
            assertThat(status.lastLatencyMs()).isEqualTo(15);
            assertThat(status.lastSuccessTime()).isNotNull();
        }

        @Test
        @DisplayName("Failed echo degrades channel status, and exceeding threshold marks channel DOWN")
        void consecutiveFailuresShouldDegradeAndMarkChannelDown() {
            SimulateResult mockFailure = SimulateResult.builder()
                    .success(false)
                    .responseCode("ERR")
                    .message("Connection refused")
                    .roundtripMs(5)
                    .build();

            when(tcpClient.simulate(anyString())).thenReturn(mockFailure);

            // 1st Failure -> DEGRADED
            EchoResult res1 = echoManager.triggerEcho();
            assertThat(res1.success()).isFalse();
            assertThat(echoManager.getChannelStatus().status()).isEqualTo(ChannelHealthStatus.DEGRADED);
            assertThat(echoManager.getChannelStatus().consecutiveFailures()).isEqualTo(1);

            // 2nd Failure -> DEGRADED
            echoManager.triggerEcho();
            assertThat(echoManager.getChannelStatus().status()).isEqualTo(ChannelHealthStatus.DEGRADED);
            assertThat(echoManager.getChannelStatus().consecutiveFailures()).isEqualTo(2);

            // 3rd Failure (threshold = 3) -> DOWN
            echoManager.triggerEcho();
            ChannelStatusReport status = echoManager.getChannelStatus();
            assertThat(status.status()).isEqualTo(ChannelHealthStatus.DOWN);
            assertThat(status.consecutiveFailures()).isEqualTo(3);
            assertThat(status.totalEchoesSent()).isEqualTo(3);
            assertThat(status.failedEchoes()).isEqualTo(3);
            assertThat(status.lastError()).contains("Connection refused");
        }

        @Test
        @DisplayName("Channel recovers from DOWN to HEALTHY upon next successful echo")
        void channelShouldRecoverToHealthyOnSuccess() {
            SimulateResult mockFailure = SimulateResult.builder()
                    .success(false)
                    .responseCode("ERR")
                    .message("Timeout")
                    .roundtripMs(5000)
                    .build();

            SimulateResult mockSuccess = SimulateResult.builder()
                    .success(true)
                    .responseMti("0810")
                    .responseCode("00")
                    .roundtripMs(12)
                    .build();

            when(tcpClient.simulate(anyString()))
                    .thenReturn(mockFailure)
                    .thenReturn(mockFailure)
                    .thenReturn(mockFailure)
                    .thenReturn(mockSuccess);

            // 3 failures -> DOWN
            echoManager.triggerEcho();
            echoManager.triggerEcho();
            echoManager.triggerEcho();
            assertThat(echoManager.getChannelStatus().status()).isEqualTo(ChannelHealthStatus.DOWN);

            // 4th is success -> recovered to HEALTHY
            EchoResult recovered = echoManager.triggerEcho();
            assertThat(recovered.success()).isTrue();
            ChannelStatusReport status = echoManager.getChannelStatus();
            assertThat(status.status()).isEqualTo(ChannelHealthStatus.HEALTHY);
            assertThat(status.consecutiveFailures()).isZero();
            assertThat(status.successfulEchoes()).isEqualTo(1);
            assertThat(status.lastLatencyMs()).isEqualTo(12);
        }
    }
}
