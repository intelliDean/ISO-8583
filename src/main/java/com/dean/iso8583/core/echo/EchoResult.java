package com.dean.iso8583.core.echo;

import java.time.Instant;

/**
 * Developer Note:
 * Structured result of an individual ISO 8583 0800 Network Management Echo Test.
 *
 * @param success               whether the 0810 response was received and approved (DE 39 == "00")
 * @param roundtripMs           total TCP roundtrip network latency in milliseconds
 * @param stan                  Systems Trace Audit Number (DE 11) generated for this echo
 * @param transmissionDateTime  Transmission Date & Time (DE 7 in MMDDhhmmss)
 * @param networkManagementCode DE 70 code (e.g. "301" for Echo Test)
 * @param responseMti           response MTI received (e.g. "0810")
 * @param responseCode          response code (DE 39) received from the peer host
 * @param rawRequest            the packed 0800 request string sent over the socket
 * @param rawResponse           the raw response payload received back
 * @param errorMessage          error details if the socket write/read or validation failed
 * @param timestamp             timestamp when the echo was executed
 */
public record EchoResult(
        boolean success,
        long roundtripMs,
        String stan,
        String transmissionDateTime,
        String networkManagementCode,
        String responseMti,
        String responseCode,
        String rawRequest,
        String rawResponse,
        String errorMessage,
        Instant timestamp
) {
    public static EchoResult success(
            long roundtripMs,
            String stan,
            String transmissionDateTime,
            String responseMti,
            String responseCode,
            String rawRequest,
            String rawResponse
    ) {
        return new EchoResult(
                true,
                roundtripMs,
                stan,
                transmissionDateTime,
                NetworkManagementCode.ECHO_TEST.getCode(),
                responseMti,
                responseCode,
                rawRequest,
                rawResponse,
                null,
                Instant.now()
        );
    }

    public static EchoResult failure(
            long roundtripMs,
            String stan,
            String transmissionDateTime,
            String rawRequest,
            String errorMessage
    ) {
        return new EchoResult(
                false,
                roundtripMs,
                stan,
                transmissionDateTime,
                NetworkManagementCode.ECHO_TEST.getCode(),
                null,
                "ERR",
                rawRequest,
                null,
                errorMessage,
                Instant.now()
        );
    }
}
