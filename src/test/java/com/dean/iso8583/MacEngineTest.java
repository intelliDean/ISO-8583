package com.dean.iso8583;

import com.dean.iso8583.core.crypto.CryptoUtils;
import com.dean.iso8583.core.crypto.MacEngine;
import com.dean.iso8583.core.dto.IsoMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MacEngine Test Suite")
class MacEngineTest {

    private static final byte[] MAC_KEY = CryptoUtils.hexToBytes("0123456789ABCDEFFEDCBA9876543210");

    @Test
    @DisplayName("Applies ISO 9797-1 Padding Method 2 correctly")
    void shouldApplyPaddingMethod2() {
        byte[] data8 = "12345678".getBytes(StandardCharsets.US_ASCII);
        byte[] padded8 = MacEngine.padMethod2(data8);
        assertThat(padded8).hasSize(16);
        assertThat(padded8[8]).isEqualTo((byte) 0x80);

        byte[] data7 = "1234567".getBytes(StandardCharsets.US_ASCII);
        byte[] padded7 = MacEngine.padMethod2(data7);
        assertThat(padded7).hasSize(8);
        assertThat(padded7[7]).isEqualTo((byte) 0x80);
    }

    @Test
    @DisplayName("Calculates deterministic 16-hex Retail MAC (Algorithm 3)")
    void shouldCalculateRetailMac() {
        byte[] payload = "600000000002007220000000C080001645320155889912340000000000000025500814003651000999TERM0001MERCHANT1234567840"
                .getBytes(StandardCharsets.US_ASCII);

        String mac1 = MacEngine.calculateRetailMac(payload, MAC_KEY);
        String mac2 = MacEngine.calculateRetailMac(payload, MAC_KEY);

        assertThat(mac1).hasSize(16);
        assertThat(mac1).isEqualTo(mac2);
    }

    @Test
    @DisplayName("Calculates and verifies message MAC in DE 64")
    void shouldCalculateAndVerifyMessageMac() {
        IsoMessage msg = new IsoMessage("0200");
        msg.setHeader("6000000000");
        msg.setField(2, "4532015588991234");
        msg.setField(3, "000000");
        msg.setField(4, "000000002550");
        msg.setField(11, "000123");

        String mac = MacEngine.calculateMessageMac(msg, MAC_KEY);
        msg.setField(64, mac);

        boolean verified = MacEngine.verifyMessageMac(msg, MAC_KEY);
        assertThat(verified).isTrue();

        // Tamper with amount
        msg.setField(4, "000000009999");
        boolean tamperedVerify = MacEngine.verifyMessageMac(msg, MAC_KEY);
        assertThat(tamperedVerify).isFalse();
    }
}
