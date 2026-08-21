package com.dean.iso8583.web.service;

import com.dean.iso8583.core.clearing.dto.ClearingBatch;
import com.dean.iso8583.core.clearing.dto.ClearingRecord;
import com.dean.iso8583.core.crypto.dto.DukptDtos;
import com.dean.iso8583.core.dto.IsoDTOs;
import com.dean.iso8583.core.echo.dto.ChannelStatusReport;
import com.dean.iso8583.core.echo.dto.EchoResult;
import com.dean.iso8583.core.reversal.TransactionRecord;
import com.dean.iso8583.web.data.dto.*;

import java.util.Collection;
import java.util.Map;

public interface ISO8583Service {

    Map<Integer, IsoDTOs.IsoFieldDef> getCatalog();

    Map<String, IsoDTOs.IsoSpecDefinition> getSpecs();

    WebDTOs.UnpackResult unpackMessage(WebDTOs.UnpackRequest request);

    WebDTOs.PackResult packMessage(WebDTOs.PackRequest request);

    WebDTOs.SimulateResult simulateTransaction(WebDTOs.SimulateRequest request);

    /**
     * <p>Parses the hex-encoded DE 55 BER-TLV stream from an EMV chip/contactless
     * transaction into its constituent tag-length-value triplets.</p>
     *
     * @param request contains the raw DE 55 hex string
     * @return structured parse result with all decoded tags and fraud signals
     */
    WebDTOs.EmvParseResponse parseEmv(WebDTOs.EmvParseRequest request);

    /**
     * Returns all tracked transaction records from the state store.
     *
     * @return collection of transaction records
     */
    Collection<TransactionRecord> getTransactions();

    /**
     * Executes an on-demand ISO 8583 0800 Keep-Alive Echo test against the peer host.
     *
     * @return result of the echo test execution including roundtrip latency and response code
     */
    EchoResult triggerEcho();

    /**
     * Retrieves the current communication channel health telemetry and statistics report.
     *
     * @return channel status report
     */
    ChannelStatusReport getEchoStatus();

    /**
     * Encodes and encrypts a clear PIN into an ISO 9564 PIN block (DE 52).
     */
    WebDTOs.PinEncodeResponse encodePin(WebDTOs.PinEncodeRequest request);

    /**
     * Translates an encrypted PIN block across different key zones or block formats.
     */
    WebDTOs.PinTranslateResponse translatePin(WebDTOs.PinTranslateRequest request);

    /**
     * Calculates an ISO 9797-1 Retail MAC over an ISO 8583 message.
     */
    WebDTOs.MacGenerateResponse generateMac(WebDTOs.MacGenerateRequest request);

    /**
     * Verifies the ISO 9797-1 Retail MAC of an ISO 8583 message.
     */
    WebDTOs.MacVerifyResponse verifyMac(WebDTOs.MacVerifyRequest request);

    /**
     * Generates an end-of-day Dual-Message System (DMS) 1240 settlement clearing batch.
     */
    ClearingBatch generateClearingBatch(WebDTOs.ClearingBatchRequest request);

    /**
     * Files a 1440 Chargeback dispute against a settled transaction.
     */
    ClearingRecord fileChargeback(WebDTOs.ChargebackRequest request);

    /**
     * Parses an incoming raw batch clearing file.
     */
    ClearingBatch parseClearingBatch(WebDTOs.ClearingParseRequest request);

    /**
     * Retrieves all archived clearing batches.
     */
    Collection<ClearingBatch> getClearingBatches();

    /**
     * Retrieves all filed chargeback records.
     */
    Collection<ClearingRecord> getChargebacks();

    /**
     * Retrieves distributed persistence, distributed locking, and Kafka Outbox telemetry.
     */
    WebDTOs.ResiliencyStatusResponse getResiliencyStatus();

    /**
     * Derives Initial PIN Encryption Key (IPEK) from BDK and KSN.
     */
   DukptDtos.DeriveIpekResponse deriveDukptIpek(DukptDtos.DeriveIpekRequest request);

    /**
     * Derives Transaction Key and variants (PEK, MAK, DEK) from BDK/IPEK and KSN.
     */
    DukptDtos.DeriveKeyResponse deriveDukptKey(DukptDtos.DeriveKeyRequest request);

    /**
     * Encrypts a PIN block using the terminal's derived DUKPT PEK key.
     */
    DukptDtos.EncryptDukptPinResponse encryptDukptPin(DukptDtos.EncryptDukptPinRequest request);

    /**
     * Decrypts a DUKPT-encrypted PIN block using BDK and KSN.
     */
    DukptDtos.DecryptDukptPinResponse decryptDukptPin(DukptDtos.DecryptDukptPinRequest request);
}
