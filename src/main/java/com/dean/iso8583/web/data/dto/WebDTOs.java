package com.dean.iso8583.web.data.dto;

import com.dean.iso8583.core.crypto.PinBlockFormat;
import com.dean.iso8583.core.emv.dto.EmvParseResult;
import com.dean.iso8583.core.emv.dto.EmvTag;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Builder;

import java.util.List;
import java.util.Map;

public final class WebDTOs {

    private WebDTOs() {
    }

    /**
     * Request body for filing a 1440 Chargeback dispute.
     *
     * @param stan              original transaction STAN
     * @param maskedPan         cardholder masked PAN
     * @param amountIso         12-digit dispute amount
     * @param disputeReasonCode reason code (e.g. "4837" Fraud, "4853" Recurring Transaction Canceled)
     */
    public record ChargebackRequest(
            String stan,
            String maskedPan,
            String amountIso,
            String disputeReasonCode
    ) {
    }

    /**
     * Request body for generating an end-of-day settlement clearing batch.
     *
     * @param networkId target clearing network (e.g. "MASTERCARD-IPM", "VISA-BASE2")
     */
    public record ClearingBatchRequest(
            String networkId
    ) {
    }

    /**
     * Request body for parsing a raw batch clearing file.
     *
     * @param rawBatchFile raw text of the clearing batch file
     */
    public record ClearingParseRequest(
            String rawBatchFile
    ) {
    }

    /**
     * Developer Note:
     * <p>Request body for POST /api/iso/emv/parse.
     *
     * <p>{@code de55Hex} is the hex-encoded value of ISO 8583 Data Element 55
     * (ICC System Related Data) as received in the authorization message.</p>
     * <p>This is a raw hex dump of the BER-TLV stream embedded in the ISO message.</p>
     * <p>
     * Example:
     * <pre>{@code
     * {
     *   "de55Hex": "9F2608A1B2C3D4E5F6079F360200E29F10120110A000002A0000000000000000000000FF"
     * }
     * }</pre>
     *
     * @param de55Hex hex-encoded DE 55 BER-TLV payload
     */
    public record EmvParseRequest(String de55Hex) {
    }

    /**
     * Developer Note:
     * <p>REST response DTO for the POST /api/iso/emv/parse endpoint.</p>
     *
     * <p> Mirrors {@link EmvParseResult} but lives in the web
     * layer so the core domain model remains decoupled from the HTTP transport.</p>
     *
     * @param rawHex     the original DE 55 hex string submitted by the caller
     * @param tagCount   total number of TLV elements decoded
     * @param tags       ordered list of decoded EMV tag elements
     * @param hasArqc    whether an ARQC (9F26) is present — key fraud signal
     * @param hasAtc     whether an ATC (9F36) is present
     * @param arqcValue  value of the ARQC tag if present, else null
     * @param atcValue   value of the ATC tag if present (hex), else null
     * @param atcDecimal ATC as a decimal integer for easier monitoring dashboards
     */
    public record EmvParseResponse(
            String rawHex,
            int tagCount,
            List<EmvTag> tags,
            boolean hasArqc,
            boolean hasAtc,
            String arqcValue,
            String atcValue,
            Integer atcDecimal
    ) {
    }

    public record FieldDetail(
            int fieldId,
            String name,
            String type,
            String value
    ) {
    }

    /**
     * Request body for generating an ISO 9797-1 Retail MAC over an ISO 8583 message.
     *
     * @param rawPayload raw ISO 8583 message string (or null if providing pack fields)
     * @param keyHex     16-byte MAC key hex (or null to use keyId)
     * @param keyId      registered MAC key identifier (e.g. "DEFAULT_MAK")
     */
    public record MacGenerateRequest(
            String rawPayload,
            String keyHex,
            String keyId
    ) {
    }

    /**
     * Response body containing the generated ISO 9797-1 Retail MAC.
     *
     * @param macHex    16-hex character MAC suitable for DE 64 or DE 128
     * @param algorithm cryptographic algorithm used
     * @param keyUsed   identifier of key used
     */
    public record MacGenerateResponse(
            String macHex,
            String algorithm,
            String keyUsed
    ) {
    }

    /**
     * Request body for verifying the MAC of an ISO 8583 message.
     *
     * @param rawPayload  raw ISO 8583 message string
     * @param expectedMac 16-hex character MAC (optional if message contains DE 64/DE 128)
     * @param keyHex      16-byte MAC key hex (optional)
     * @param keyId       registered key ID (optional)
     */
    public record MacVerifyRequest(
            String rawPayload,
            String expectedMac,
            String keyHex,
            String keyId
    ) {
    }

    /**
     * Response body for MAC verification.
     *
     * @param valid            true if MAC matched; false otherwise
     * @param calculatedMacHex calculated MAC string
     * @param message          status message
     */
    public record MacVerifyResponse(
            boolean valid,
            String calculatedMacHex,
            String message
    ) {
    }

    public record PackRequest(
            String header,
            String mti,
            Map<Object, String> fields,
            String specId
    ) {
        public PackRequest {
            fields = fields == null
                    ? Map.of()
                    : Map.copyOf(fields);
        }
    }

    public record PackResult(
            String rawPayload,
            String primaryBitmapHex,
            String secondaryBitmapHex,
            int length
    ) {
    }

    /**
     * Request body for encoding and encrypting a PIN block.
     *
     * @param pin    plaintext PIN (4-12 digits)
     * @param pan    Primary Account Number (required for Format 0 and Format 3)
     * @param format target PIN block format (FORMAT_0, FORMAT_1, FORMAT_3, FORMAT_4)
     * @param keyHex optional 16/24/32-byte hex key string (if null, uses default ZPK)
     * @param keyId  optional registered key identifier in CryptoKeyRegistry
     */
    public record PinEncodeRequest(
            String pin,
            String pan,
            PinBlockFormat format,
            String keyHex,
            String keyId
    ) {
    }

    /**
     * Response body for PIN block encoding.
     *
     * @param clearBlockHex     16 or 32-hex clear PIN block (for testing/debug)
     * @param encryptedBlockHex encrypted PIN block (suitable for DE 52)
     * @param format            PIN block format used
     * @param keyUsed           name or status of key used for encryption
     */
    public record PinEncodeResponse(
            String clearBlockHex,
            String encryptedBlockHex,
            PinBlockFormat format,
            String keyUsed
    ) {
    }


    /**
     * Request body for atomic cross-zone PIN block translation.
     *
     * @param encryptedBlockHex incoming DE 52 encrypted PIN block
     * @param srcPan            source PAN
     * @param srcFormat         source format
     * @param srcKeyHex         source key hex (or null to use keyId)
     * @param srcKeyId          source key identifier (e.g. "DEFAULT_ZPK_ACQ")
     * @param dstPan            destination PAN (if different from srcPan, else null to reuse srcPan)
     * @param dstFormat         destination format
     * @param dstKeyHex         destination key hex (or null to use keyId)
     * @param dstKeyId          destination key identifier (e.g. "DEFAULT_ZPK_ISS")
     */
    public record PinTranslateRequest(
            String encryptedBlockHex,
            String srcPan,
            PinBlockFormat srcFormat,
            String srcKeyHex,
            String srcKeyId,
            String dstPan,
            PinBlockFormat dstFormat,
            String dstKeyHex,
            String dstKeyId
    ) {
    }

    /**
     * Response body for PIN block translation.
     *
     * @param translatedBlockHex newly re-encrypted PIN block under destination key and format
     * @param srcFormat          source format
     * @param dstFormat          destination format
     * @param success            status flag
     */
    public record PinTranslateResponse(
            String translatedBlockHex,
            PinBlockFormat srcFormat,
            PinBlockFormat dstFormat,
            boolean success
    ) {
    }


    /**
     * Telemetry response containing distributed persistence, state locking,
     * and Kafka event streaming status.
     */
    public record ResiliencyStatusResponse(
            String persistenceEngine,
            int totalTransactions,
            int totalClearingBatches,
            int totalChargebacks,
            String lockEngine,
            String outboxStatus,
            int pendingOutboxEvents,
            long totalEventsDispatched,
            List<com.dean.iso8583.core.event.IsoOutboxEvent> recentEvents
    ) {
    }

    public record SimulateRequest(
            @JsonAlias({"payload", "message", "requestPayload", "sentRaw"})
            String rawPayload
    ) {
    }

    @Builder
    public record SimulateResult(
            String requestPayload,
            String responsePayload,
            String responseMti,
            String responseCode,
            String responseCodeDescription,
            long roundtripMs,
            boolean success,
            String message
    ) {
    }

    public record UnpackRequest(
            @JsonAlias({"rawPayload", "message"})
            String payload,
            boolean hasHeader,
            String specId
    ) {
    }

    public record UnpackResult(
            String header,
            String mti,
            String mtiDescription,
            String primaryBitmapHex,
            String secondaryBitmapHex,
            List<Integer> activeFields,
            List<WebDTOs.FieldDetail> fields
    ) {
    }


}
