package com.dean.iso8583.core.emv.dto;

import lombok.Builder;

import java.util.List;

/**
 * Developer Note:<br>
 * <p>Immutable result container for a parsed DE 55 EMV TLV stream.</p>
 *
 * <p>The {@link #tags()} list preserves the original byte-order from the stream,
 * which is important for ARQC cryptogram verification — the order of TLV
 * values fed into the session key derivation function is strictly defined by
 * the EMV standard.</p>
 *
 * <p>{@link #rawHex()} is retained so downstream services (e.g. HSM adapters)
 * can forward the original octets without re-encoding.</p>
 *
 * @param rawHex  the original DE 55 hex string that was parsed
 * @param tags    ordered list of decoded {@link EmvTag} elements
 */
@Builder
public record EmvParseResult(
        String rawHex,
        List<EmvTag> tags
) {
    /**
     * Convenience lookup: returns the value of the first tag matching
     * {@code tagHex}, or {@code null} if not present.
     *
     * <p>Developer Note: Used by issuer host validation logic to quickly
     * extract ARQC ({@code 9F26}) and ATC ({@code 9F36}) without iterating
     * the full tag list.</p>
     *
     * @param tagHex hex tag identifier, e.g. {@code "9F26"}
     * @return hex-encoded value string, or {@code null}
     */
    public String getValue(String tagHex) {
        return tags.stream()
                .filter(t -> t.tag().equalsIgnoreCase(tagHex))
                .map(EmvTag::value)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns {@code true} if the parsed result contains the specified tag.
     *
     * @param tagHex hex tag identifier
     * @return {@code true} if present
     */
    public boolean hasTag(String tagHex) {
        return getValue(tagHex) != null;
    }
}
