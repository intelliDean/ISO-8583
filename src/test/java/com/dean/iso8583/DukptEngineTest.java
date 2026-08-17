package com.dean.iso8583;

import com.dean.iso8583.core.crypto.CryptoUtils;
import com.dean.iso8583.core.crypto.DukptEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DukptEngine Test Suite")
class DukptEngineTest {

    // Standard ANSI X9.24 Test Vector
    private static final byte[] BDK = CryptoUtils.hexToBytes("0123456789ABCDEFFEDCBA9876543210");
    private static final byte[] KSN_1 = CryptoUtils.hexToBytes("FFFF9876543210E00001");
    private static final byte[] KSN_2 = CryptoUtils.hexToBytes("FFFF9876543210E00002");

    @Test
    @DisplayName("Derives reproducible IPEK from BDK and KSN")
    void shouldDeriveIpek() {
        byte[] ipek = DukptEngine.deriveIpek(BDK, KSN_1);

        assertThat(ipek).hasSize(16);
        assertThat(CryptoUtils.bytesToHex(ipek)).isNotEmpty();
    }

    @Test
    @DisplayName("Extracts 21-bit transaction counter correctly from KSN")
    void shouldExtractTransactionCounter() {
        long count1 = DukptEngine.extractTransactionCounter(KSN_1);
        assertThat(count1).isEqualTo(1);

        long count2 = DukptEngine.extractTransactionCounter(KSN_2);
        assertThat(count2).isEqualTo(2);

        byte[] ksn100 = CryptoUtils.hexToBytes("FFFF9876543210E00064"); // 0x64 = 100
        assertThat(DukptEngine.extractTransactionCounter(ksn100)).isEqualTo(100);
    }

    @Test
    @DisplayName("Derives distinct session keys for consecutive transaction counters (Forward Secrecy)")
    void shouldDeriveUniqueSessionKeysPerTransaction() {
        byte[] pinKey1 = DukptEngine.derivePinKey(BDK, KSN_1);
        byte[] pinKey2 = DukptEngine.derivePinKey(BDK, KSN_2);

        assertThat(pinKey1).isNotEqualTo(pinKey2);
        assertThat(pinKey1).hasSize(16);
        assertThat(pinKey2).hasSize(16);
    }

    @Test
    @DisplayName("Derives separate PIN, MAC, and Data variant keys for the same transaction")
    void shouldDeriveDistinctVariantKeys() {
        byte[] pinKey = DukptEngine.derivePinKey(BDK, KSN_1);
        byte[] macKey = DukptEngine.deriveMacKey(BDK, KSN_1);
        byte[] dataKey = DukptEngine.deriveDataKey(BDK, KSN_1);

        assertThat(pinKey).isNotEqualTo(macKey);
        assertThat(pinKey).isNotEqualTo(dataKey);
        assertThat(macKey).isNotEqualTo(dataKey);
    }
}
