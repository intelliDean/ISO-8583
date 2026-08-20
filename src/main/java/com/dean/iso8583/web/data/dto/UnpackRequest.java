package com.dean.iso8583.web.data.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record UnpackRequest(
        @JsonAlias({"rawPayload", "message"})
        String payload,
        boolean hasHeader,
        String specId
) {
}
