package com.dean.iso8583.web.data.dto;

/**
 * Request body for parsing a raw batch clearing file.
 *
 * @param rawBatchFile raw text of the clearing batch file
 */
public record ClearingParseRequest(
        String rawBatchFile
) {
}
