package com.dean.iso8583.web.data.dto;

import com.dean.iso8583.core.emv.EmvTag;

import java.util.List;

/**
 * Developer Note:
 * REST response DTO for the POST /api/iso/emv/parse endpoint.
 *
 * Mirrors {@link com.dean.iso8583.core.emv.EmvParseResult} but lives in the web
 * layer so the core domain model remains decoupled from the HTTP transport.
 *
 * @param rawHex       the original DE 55 hex string submitted by the caller
 * @param tagCount     total number of TLV elements decoded
 * @param tags         ordered list of decoded EMV tag elements
 * @param hasArqc      whether an ARQC (9F26) is present — key fraud signal
 * @param hasAtc       whether an ATC (9F36) is present
 * @param arqcValue    value of the ARQC tag if present, else null
 * @param atcValue     value of the ATC tag if present (hex), else null
 * @param atcDecimal   ATC as a decimal integer for easier monitoring dashboards
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
