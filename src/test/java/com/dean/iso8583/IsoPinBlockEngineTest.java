package com.dean.iso8583;

import com.dean.iso8583.core.crypto.CryptoUtils;
import com.dean.iso8583.core.crypto.IsoPinBlockEngine;
import com.dean.iso8583.core.crypto.PinBlockFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("IsoPinBlockEngine Test Suite")
class IsoPinBlockEngineTest {

    private static final String SAMPLE_PAN = "4532015588991234";
    private static final String SAMPLE_PIN = "1234";
    private static final byte[] SAMPLE_KEY = CryptoUtils.hexToBytes("0123456789ABCDEFFEDCBA9876543210");
    private static final byte[] ISSUER_KEY = CryptoUtils.hexToBytes("FEDCBA98765432100123456789ABCDEF");

    @Nested
    @DisplayName("Format 0 (ANSI X9.8 / ISO-0) Tests")
    class Format0Tests {

        @Test
        @DisplayName("Encodes and decodes Format 0 PIN block matching PAN XOR specification")
        void shouldEncodeAndDecodeFormat0() {
            // PIN field: 041234FFFFFFFFFF
            // PAN field: 0000201558899123 (from PAN 4532015588991234)
            // Expected XOR: 041214EAA7766EDC
            String clearBlock = IsoPinBlockEngine.encodeClearPinBlock(SAMPLE_PIN, SAMPLE_PAN, PinBlockFormat.FORMAT_0);
            assertThat(clearBlock).isEqualTo("041214EAA7766EDC");

            String decodedPin = IsoPinBlockEngine.decodeClearPinBlock(clearBlock, SAMPLE_PAN, PinBlockFormat.FORMAT_0);
            assertThat(decodedPin).isEqualTo(SAMPLE_PIN);
        }

        @Test
        @DisplayName("Encrypts and decrypts Format 0 PIN block with 3DES")
        void shouldEncryptAndDecryptFormat0() {
            String encryptedBlock = IsoPinBlockEngine.encryptPin(SAMPLE_PIN, SAMPLE_PAN, PinBlockFormat.FORMAT_0, SAMPLE_KEY);
            assertThat(encryptedBlock).hasSize(16);

            String decryptedPin = IsoPinBlockEngine.decryptPin(encryptedBlock, SAMPLE_PAN, PinBlockFormat.FORMAT_0, SAMPLE_KEY);
            assertThat(decryptedPin).isEqualTo(SAMPLE_PIN);
        }
    }

    @Nested
    @DisplayName("Format 1 (ISO-1) Tests")
    class Format1Tests {

        @Test
        @DisplayName("Encodes and decodes Format 1 transaction-independent PIN block")
        void shouldEncodeAndDecodeFormat1() {
            String clearBlock = IsoPinBlockEngine.encodeClearPinBlock("5678", null, PinBlockFormat.FORMAT_1);
            assertThat(clearBlock).startsWith("145678");
            assertThat(clearBlock).hasSize(16);

            String decodedPin = IsoPinBlockEngine.decodeClearPinBlock(clearBlock, null, PinBlockFormat.FORMAT_1);
            assertThat(decodedPin).isEqualTo("5678");
        }
    }

    @Nested
    @DisplayName("Format 3 (ISO-3) Tests")
    class Format3Tests {

        @Test
        @DisplayName("Encodes and decodes Format 3 PIN block")
        void shouldEncodeAndDecodeFormat3() {
            String clearBlock = IsoPinBlockEngine.encodeClearPinBlock("9999", SAMPLE_PAN, PinBlockFormat.FORMAT_3);
            assertThat(clearBlock).hasSize(16);

            String decodedPin = IsoPinBlockEngine.decodeClearPinBlock(clearBlock, SAMPLE_PAN, PinBlockFormat.FORMAT_3);
            assertThat(decodedPin).isEqualTo("9999");
        }
    }

    @Nested
    @DisplayName("Format 4 (ISO-4 AES) Tests")
    class Format4Tests {

        @Test
        @DisplayName("Encodes and decodes 16-byte Format 4 AES PIN block")
        void shouldEncodeAndDecodeFormat4() {
            String clearBlock = IsoPinBlockEngine.encodeClearPinBlock("123456", SAMPLE_PAN, PinBlockFormat.FORMAT_4);
            assertThat(clearBlock).hasSize(32); // 16 bytes = 32 hex chars

            String decodedPin = IsoPinBlockEngine.decodeClearPinBlock(clearBlock, SAMPLE_PAN, PinBlockFormat.FORMAT_4);
            assertThat(decodedPin).isEqualTo("123456");
        }

        @Test
        @DisplayName("Encrypts and decrypts Format 4 with AES-128")
        void shouldEncryptAndDecryptFormat4() {
            byte[] aesKey = CryptoUtils.hexToBytes("000102030405060708090A0B0C0D0E0F");
            String encryptedBlock = IsoPinBlockEngine.encryptPin("4321", SAMPLE_PAN, PinBlockFormat.FORMAT_4, aesKey);
            assertThat(encryptedBlock).hasSize(32);

            String decryptedPin = IsoPinBlockEngine.decryptPin(encryptedBlock, SAMPLE_PAN, PinBlockFormat.FORMAT_4, aesKey);
            assertThat(decryptedPin).isEqualTo("4321");
        }
    }

    @Nested
    @DisplayName("Cross-Zone PIN Block Translation Tests")
    class TranslationTests {

        @Test
        @DisplayName("Translates PIN block between Acquirer Key and Issuer Key atomically")
        void shouldTranslatePinBlockAcrossKeys() {
            // Acquirer produces encrypted Format 0 block
            String acqEncrypted = IsoPinBlockEngine.encryptPin("8765", SAMPLE_PAN, PinBlockFormat.FORMAT_0, SAMPLE_KEY);

            // Switch translates to Issuer Format 1 under Issuer key
            String issEncrypted = IsoPinBlockEngine.translatePinBlock(
                    acqEncrypted,
                    SAMPLE_PAN,
                    PinBlockFormat.FORMAT_0,
                    SAMPLE_KEY,
                    SAMPLE_PAN,
                    PinBlockFormat.FORMAT_1,
                    ISSUER_KEY
            );

            assertThat(issEncrypted).isNotEqualTo(acqEncrypted);

            // Issuer decrypts and verifies PIN matches
            String issuerDecryptedPin = IsoPinBlockEngine.decryptPin(issEncrypted, SAMPLE_PAN, PinBlockFormat.FORMAT_1, ISSUER_KEY);
            assertThat(issuerDecryptedPin).isEqualTo("8765");
        }
    }

    @Nested
    @DisplayName("Validation Error Tests")
    class ValidationTests {

        @Test
        @DisplayName("Rejects PINs with invalid lengths or non-numeric characters")
        void shouldRejectInvalidPins() {
            assertThatThrownBy(() -> IsoPinBlockEngine.encodeClearPinBlock("12", SAMPLE_PAN, PinBlockFormat.FORMAT_0))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> IsoPinBlockEngine.encodeClearPinBlock("123A", SAMPLE_PAN, PinBlockFormat.FORMAT_0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
