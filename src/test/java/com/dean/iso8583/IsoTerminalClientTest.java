package com.dean.iso8583;

import com.dean.iso8583.client.IsoTerminalClient;
import com.dean.iso8583.core.dto.IsoMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@DisplayName("IsoTerminalClient Integration Test")
class IsoTerminalClientTest {

    @Test
    @DisplayName("IsoTerminalClient successfully performs 0800 echo over TCP")
    void shouldSendAndReceiveEcho() throws Exception {
        IsoTerminalClient client = new IsoTerminalClient("localhost", 8583, "6000000000");

        IsoMessage response = client.sendEcho("000001");

        assertThat(response).isNotNull();
        assertThat(response.getMti()).isEqualTo("0810");
        assertThat(response.getField(39)).isEqualTo("00");
    }

    @Test
    @DisplayName("IsoTerminalClient successfully performs 0200 Purchase and 0400 Reversal flow")
    void shouldPerformPurchaseAndReversal() throws Exception {
        IsoTerminalClient client = new IsoTerminalClient("localhost", 8583, "6000000000");

        // 1. Send 0200 Purchase
        IsoMessage authResp = client.sendPurchase("4532015588991234", "00000002550", "000999", "TERM0001", "MERCHANT1234567");
        assertThat(authResp.getMti()).isEqualTo("0210");
        assertThat(authResp.getField(39)).isEqualTo("00");

        // 2. Send 0400 Reversal for same STAN
        IsoMessage revResp = client.sendReversal("4532015588991234", "00000002550", "000999", "TERM0001", "MERCHANT1234567");
        assertThat(revResp.getMti()).isEqualTo("0410");
        assertThat(revResp.getField(39)).isEqualTo("00");
    }
}
