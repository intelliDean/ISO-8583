package com.dean.iso8583.web.data.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record SimulateRequest(
        @JsonAlias({"payload", "message", "requestPayload", "sentRaw"})
        String rawPayload
) {
}
