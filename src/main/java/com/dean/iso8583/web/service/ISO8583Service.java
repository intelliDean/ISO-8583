package com.dean.iso8583.web.service;

import com.dean.iso8583.core.dto.IsoFieldDef;
import com.dean.iso8583.core.dto.IsoSpecDefinition;
import com.dean.iso8583.core.echo.dto.ChannelStatusReport;
import com.dean.iso8583.core.echo.dto.EchoResult;
import com.dean.iso8583.web.data.dto.*;

import java.util.Map;

public interface ISO8583Service {

    Map<Integer, IsoFieldDef> getCatalog();

    Map<String, IsoSpecDefinition> getSpecs();

    UnpackResult unpackMessage(UnpackRequest request);

    PackResult packMessage(PackRequest request);

    SimulateResult simulateTransaction(SimulateRequest request);

    /**
     * <p>Parses the hex-encoded DE 55 BER-TLV stream from an EMV chip/contactless
     * transaction into its constituent tag-length-value triplets.</p>
     *
     * @param request contains the raw DE 55 hex string
     * @return structured parse result with all decoded tags and fraud signals
     */
    EmvParseResponse parseEmv(EmvParseRequest request);

    /**
     * Returns all tracked transaction records from the state store.
     *
     * @return collection of transaction records
     */
    java.util.Collection<com.dean.iso8583.core.reversal.TransactionRecord> getTransactions();

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
    PinEncodeResponse encodePin(PinEncodeRequest request);

    /**
     * Translates an encrypted PIN block across different key zones or block formats.
     */
    PinTranslateResponse translatePin(PinTranslateRequest request);

    /**
     * Calculates an ISO 9797-1 Retail MAC over an ISO 8583 message.
     */
    MacGenerateResponse generateMac(MacGenerateRequest request);

    /**
     * Verifies the ISO 9797-1 Retail MAC of an ISO 8583 message.
     */
    MacVerifyResponse verifyMac(MacVerifyRequest request);
}
