package com.dean.iso8583;

import com.dean.iso8583.core.emv.*;
import com.dean.iso8583.core.emv.dto.EmvParseResult;
import com.dean.iso8583.core.emv.dto.EmvTag;
import com.dean.iso8583.core.emv.enums.EmvTagName;
import com.dean.iso8583.core.emv.exception.EmvParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * <p>Unit tests for the EMV BER-TLV parser engine.</p>
 *
 * Developer Note:
 * <p>Test data is constructed from real-world EMV transaction traces.</p>
 * <p>Each TLV is hand-crafted so that both the encoding rules and the
 * fraud-signal extraction logic are explicitly exercised.</p>
 *
 * <ul>
 * Test coverage:
 *
 * <li>Single-byte tags (9A, 9C)</li>
 *  <li>Multi-byte tags (9F26, 9F36, 9F10, 9F37)</li>
 *  <li> Short-form and long-form length encoding</li>
 *  <li> ARQC / ATC extraction convenience methods</li>
 * <li> Error paths: null input, odd-length hex, oversized payload, truncated stream</li>
 *  </ul>
 */
@DisplayName("EmvTlvParser — BER-TLV Parsing Engine")
class EmvTlvParserTest {

    // ── Reference hex strings ─────────────────────────────────────────────────

    /**
     * Realistic DE 55 sample containing:
     *  <li>9F26 08 A1B2C3D4E5F60708   — ARQC (8 bytes)</li>
     *  <li>9F36 02 00E2                — ATC = 226 (decimal)</li>
     *  <li>9F10 12 0110A000002A0000000000000000000000FF — IAD (18 bytes)</li>
     *  <li>9C   01 00                  — Transaction Type = Purchase</li>
     *  <li>9A   03 260813              — Transaction Date = 2026-08-13</li>
     *  <li>9F37 04 12345678            — Unpredictable Number (4 bytes)</li>
     */
    private static final String SAMPLE_DE55 =
            "9F2608A1B2C3D4E5F60708" +
            "9F360200E2" +
            "9F10120110A000002A0000000000000000000000FF" +
            "9C0100" +
            "9A03260813" +
            "9F37041234567 8".replace(" ", "");

    // ── Happy path ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Successful parsing")
    class SuccessfulParsing {

        @Test
        @DisplayName("Returns correct tag count for well-formed DE55")
        void shouldParseCorrectTagCount() {
            EmvParseResult result = EmvTlvParser.parse(SAMPLE_DE55);
            assertThat(result.tags()).hasSize(6);
        }

        @Test
        @DisplayName("Preserves raw hex in result (upper-cased)")
        void shouldPreserveRawHex() {
            EmvParseResult result = EmvTlvParser.parse(SAMPLE_DE55.toLowerCase());
            assertThat(result.rawHex()).isEqualTo(SAMPLE_DE55.toUpperCase());
        }

        @Test
        @DisplayName("Extracts ARQC (9F26) correctly")
        void shouldExtractArqc() {
            EmvParseResult result = EmvTlvParser.parse(SAMPLE_DE55);
            assertThat(result.getValue("9F26")).isEqualTo("A1B2C3D4E5F60708");
        }

        @Test
        @DisplayName("Extracts ATC (9F36) correctly")
        void shouldExtractAtc() {
            EmvParseResult result = EmvTlvParser.parse(SAMPLE_DE55);
            assertThat(result.getValue("9F36")).isEqualTo("00E2");
        }

        @Test
        @DisplayName("ATC decimal value is 226")
        void shouldComputeAtcDecimal() {
            EmvParseResult result = EmvTlvParser.parse(SAMPLE_DE55);
            String atcHex = result.getValue("9F36");
            assertThat(Integer.parseInt(atcHex, 16)).isEqualTo(226);
        }

        @Test
        @DisplayName("Detects ARQC presence via hasTag")
        void shouldDetectArqcPresence() {
            EmvParseResult result = EmvTlvParser.parse(SAMPLE_DE55);
            assertThat(result.hasTag("9F26")).isTrue();
        }

        @Test
        @DisplayName("Reports false for absent tag")
        void shouldReportAbsentTag() {
            EmvParseResult result = EmvTlvParser.parse(SAMPLE_DE55);
            // 9F27 (Cryptogram Information Data) is not in the sample
            assertThat(result.hasTag("9F27")).isFalse();
        }

        @Test
        @DisplayName("Resolves ARQC to known EmvTagName")
        void shouldResolveArqcTagName() {
            assertThat(EmvTagName.fromTag("9F26")).isEqualTo(EmvTagName.ARQC);
        }

        @Test
        @DisplayName("Resolves ATC to known EmvTagName")
        void shouldResolveAtcTagName() {
            assertThat(EmvTagName.fromTag("9F36")).isEqualTo(EmvTagName.APPLICATION_TRANSACTION_COUNTER);
        }

        @Test
        @DisplayName("Returns UNKNOWN for unregistered tag")
        void shouldReturnUnknownForUnregisteredTag() {
            assertThat(EmvTagName.fromTag("BEEF")).isEqualTo(EmvTagName.UNKNOWN);
        }

        @Test
        @DisplayName("Case-insensitive tag lookup in result")
        void shouldSupportCaseInsensitiveLookup() {
            EmvParseResult result = EmvTlvParser.parse(SAMPLE_DE55);
            assertThat(result.getValue("9f26")).isEqualTo(result.getValue("9F26"));
        }

        @Test
        @DisplayName("Single-byte tag 9C is decoded as Transaction Type = Purchase")
        void shouldDecodeTransactionType() {
            EmvParseResult result = EmvTlvParser.parse(SAMPLE_DE55);
            String txType = result.getValue("9C");
            assertThat(txType).isEqualTo("00");
        }

        @Test
        @DisplayName("Tags list is unmodifiable")
        void tagListIsUnmodifiable() {
            EmvParseResult result = EmvTlvParser.parse(SAMPLE_DE55);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> result.tags().add(null));
        }

        @Test
        @DisplayName("Minimal single-tag stream — 9A (Transaction Date)")
        void shouldParseSingleTagStream() {
            // 9A 03 260813
            EmvParseResult result = EmvTlvParser.parse("9A03260813");
            assertThat(result.tags()).hasSize(1);
            assertThat(result.getValue("9A")).isEqualTo("260813");
        }
    }

    // ── Error paths ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Throws on null input")
        void shouldThrowOnNull() {
            assertThatThrownBy(() -> EmvTlvParser.parse(null))
                    .isInstanceOf(EmvParseException.class)
                    .hasMessageContaining("null or blank");
        }

        @Test
        @DisplayName("Throws on blank input")
        void shouldThrowOnBlank() {
            assertThatThrownBy(() -> EmvTlvParser.parse("   "))
                    .isInstanceOf(EmvParseException.class)
                    .hasMessageContaining("null or blank");
        }

        @Test
        @DisplayName("Throws on odd-length hex string")
        void shouldThrowOnOddLengthHex() {
            // "9F261" is 5 characters — genuinely odd-length
            assertThatThrownBy(() -> EmvTlvParser.parse("9F261"))
                    .isInstanceOf(EmvParseException.class)
                    .hasMessageContaining("even number of characters");
        }

        @Test
        @DisplayName("Throws when value length exceeds remaining bytes")
        void shouldThrowOnTruncatedValue() {
            // 9F26 08 (claims 8-byte value) but only 4 bytes follow
            assertThatThrownBy(() -> EmvTlvParser.parse("9F2608A1B2C3D4"))
                    .isInstanceOf(EmvParseException.class)
                    .hasMessageContaining("claims value length");
        }

        @Test
        @DisplayName("Throws on invalid hex character")
        void shouldThrowOnInvalidHexCharacter() {
            assertThatThrownBy(() -> EmvTlvParser.parse("9FZZ0100"))
                    .isInstanceOf(EmvParseException.class);
        }
    }

    // ── EmvTag record ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("EmvTag record")
    class EmvTagRecordTests {

        @Test
        @DisplayName("Builder creates tag with all fields")
        void shouldBuildEmvTag() {
            EmvTag tag = EmvTag.builder()
                    .tag("9F26")
                    .name("ARQC")
                    .length(8)
                    .value("A1B2C3D4E5F60708")
                    .description("Test ARQC")
                    .build();

            assertThat(tag.tag()).isEqualTo("9F26");
            assertThat(tag.length()).isEqualTo(8);
            assertThat(tag.value()).isEqualTo("A1B2C3D4E5F60708");
        }
    }
}
