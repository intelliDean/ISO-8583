package com.dean.iso8583.web.service;

import com.dean.iso8583.core.dto.IsoFieldDef;
import com.dean.iso8583.core.dto.IsoSpecDefinition;
import com.dean.iso8583.web.data.dto.*;

import java.util.Map;

public interface ISO8583Service {

    Map<Integer, IsoFieldDef> getCatalog();

    Map<String, IsoSpecDefinition> getSpecs();

    UnpackResult unpackMessage(UnpackRequest request);

    PackResult packMessage(PackRequest request);

    SimulateResult simulateTransaction(SimulateRequest request);

    /**
     * Parses the hex-encoded DE 55 BER-TLV stream from an EMV chip/contactless
     * transaction into its constituent tag-length-value triplets.
     *
     * @param request contains the raw DE 55 hex string
     * @return structured parse result with all decoded tags and fraud signals
     */
    EmvParseResponse parseEmv(EmvParseRequest request);
}
