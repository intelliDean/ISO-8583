package com.dean.iso8583;

import com.dean.iso8583.core.crypto.CryptoUtils;
import com.dean.iso8583.core.crypto.DukptEngine;
import com.dean.iso8583.core.crypto.IsoPinBlockEngine;
import com.dean.iso8583.core.crypto.PinBlockFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DUKPT (ANSI X9.24) Key Management Engine Tests")
class DukptEngineTest {

    // Standard ANSI X9.24 Test Vector Key Material
    private static final String BDK_HEX = "0123456789ABCDEFFEDCBA9876543210";
    private static final String KSN_BASE_HEX = "FFFF9876543210E00000";
    private static final String KSN_TXN1_HEX = "FFFF9876543210E00001";
    private static final String KSN_TXN2_HEX = "FFFF9876543210E00002";

    @Nested
    @DisplayName("IPEK Derivation Tests")
    class IpekTests {

        @Test
        @DisplayName("Should derive deterministic 16-byte IPEK from BDK and KSN")
        void shouldDeriveIpek() {
            byte[] bdk = CryptoUtils.hexToBytes(BDK_HEX);
            byte[] ksn = CryptoUtils.hexToBytes(KSN_BASE_HEX);

            byte[] ipek = DukptEngine.deriveIpek(bdk, ksn);
            assertThat(ipek).hasSize(16);

            String ipekHex = CryptoUtils.bytesToHex(ipek);
            assertThat(ipekHex).isNotBlank();

            // Derivation from same BDK and masked KSN must be strictly repeatable
            byte[] ipek2 = DukptEngine.deriveIpek(bdk, CryptoUtils.hexToBytes(KSN_TXN1_HEX));
            assertThat(CryptoUtils.bytesToHex(ipek2)).isEqualTo(ipekHex);
        }

        @Test
        @DisplayName("Should validate BDK and KSN length requirements")
        void shouldValidateInputLengths() {
            byte[] validBdk = CryptoUtils.hexToBytes(BDK_HEX);
            byte[] invalidKsn = CryptoUtils.hexToBytes("FFFF9876543210"); // too short

            assertThatThrownBy(() -> DukptEngine.deriveIpek(validBdk, invalidKsn))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("KSN must be exactly 10 bytes");

            assertThatThrownBy(() -> DukptEngine.deriveIpek(new byte[8], CryptoUtils.hexToBytes(KSN_BASE_HEX)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("BDK must be 16 or 24 bytes");
        }
    }

    @Nested
    @DisplayName("Transaction Key & Variant Derivation Tests")
    class TransactionKeyTests {

        @Test
        @DisplayName("Should derive distinct transaction keys for sequential counter increments")
        void shouldDeriveDistinctTransactionKeys() {
            byte[] bdk = CryptoUtils.hexToBytes(BDK_HEX);
            byte[] ksn1 = CryptoUtils.hexToBytes(KSN_TXN1_HEX);
            byte[] ksn2 = CryptoUtils.hexToBytes(KSN_TXN2_HEX);

            byte[] ipek = DukptEngine.deriveIpek(bdk, ksn1);

            byte[] txnKey1 = DukptEngine.deriveTransactionKey(ipek, ksn1);
            byte[] txnKey2 = DukptEngine.deriveTransactionKey(ipek, ksn2);

            assertThat(txnKey1).hasSize(16);
            assertThat(txnKey2).hasSize(16);
            assertThat(txnKey1).isNotEqualTo(txnKey2);
        }

        @Test
        @DisplayName("Should compute standard ANSI X9.24 Key Variants (PEK, MAK, DEK)")
        void shouldDeriveKeyVariants() {
            byte[] bdk = CryptoUtils.hexToBytes(BDK_HEX);
            byte[] ksn = CryptoUtils.hexToBytes(KSN_TXN1_HEX);
            byte[] ipek = DukptEngine.deriveIpek(bdk, ksn);
            byte[] txnKey = DukptEngine.deriveTransactionKey(ipek, ksn);

            byte[] pinKey = DukptEngine.derivePinKey(txnKey);
            byte[] macKey = DukptEngine.deriveMacKey(txnKey);
            byte[] dataKey = DukptEngine.deriveDataKey(txnKey);

            assertThat(pinKey).hasSize(16).isNotEqualTo(txnKey);
            assertThat(macKey).hasSize(16).isNotEqualTo(txnKey).isNotEqualTo(pinKey);
            assertThat(dataKey).hasSize(16).isNotEqualTo(txnKey).isNotEqualTo(macKey);
        }

        @Test
        @DisplayName("Should extract KSN components accurately")
        void shouldExtractKsnComponents() {
            byte[] ksn = CryptoUtils.hexToBytes("FFFF9876543210E0002A"); // 2A = 42 decimal counter

            assertThat(DukptEngine.extractKeySetId(ksn)).isEqualTo("FFFF98");
            assertThat(DukptEngine.extractDeviceId(ksn)).isEqualTo("76543210");
            assertThat(DukptEngine.extractTransactionCounter(ksn)).isEqualTo(42L);
        }
    }

    @Nested
    @DisplayName("DUKPT PIN Block Encryption & Decryption Tests")
    class DukptPinTests {

        @Test
        @DisplayName("Should encrypt PIN with terminal PEK and successfully decrypt via DUKPT BDK")
        void shouldEncryptAndDecryptDukptPin() {
            byte[] bdk = CryptoUtils.hexToBytes(BDK_HEX);
            byte[] ksn = CryptoUtils.hexToBytes(KSN_TXN1_HEX);
            String pan = "4532015588991234";
            String clearPin = "4892";

            // 1. Terminal derives its current PEK
            byte[] ipek = DukptEngine.deriveIpek(bdk, ksn);
            byte[] txnKey = DukptEngine.deriveTransactionKey(ipek, ksn);
            byte[] terminalPek = DukptEngine.derivePinKey(txnKey);

            // 2. Terminal encrypts Format 0 PIN block
            String encryptedBlock = IsoPinBlockEngine.encryptPin(clearPin, pan, PinBlockFormat.FORMAT_0, terminalPek);
            assertThat(encryptedBlock).hasSize(16);

            // 3. Payment Switch receives encryptedBlock + KSN and decrypts via BDK
            String decryptedPin = DukptEngine.decryptDukptPin(
                    bdk,
                    ksn,
                    encryptedBlock,
                    pan,
                    PinBlockFormat.FORMAT_0
            );

            assertThat(decryptedPin).isEqualTo(clearPin);
        }
    }
}
