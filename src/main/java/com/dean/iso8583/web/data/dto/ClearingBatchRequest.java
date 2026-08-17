package com.dean.iso8583.web.data.dto;

/**
 * Request body for generating an end-of-day settlement clearing batch.
 *
 * @param networkId target clearing network (e.g. "MASTERCARD-IPM", "VISA-BASE2")
 */
public record ClearingBatchRequest(
        String networkId
) {
}
