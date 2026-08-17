package com.dean.iso8583.core.dto;

public enum IsoFieldType {
    /**
     * Fixed-length numeric field.
     *
     * Example:
     * DE 3  - Processing Code   - 6 digits
     * DE 4  - Amount            - 12 digits
     * DE 11 - STAN              - 6 digits
     * DE 39 - Response Code     - 2 digits
     */
    FIXED_NUMERIC,
    /**
     * Fixed-length alphanumeric/text field.
     *
     * Example:
     * DE 41 - Card Acceptor Terminal ID - 8 characters
     * DE 42 - Card Acceptor ID           - 15 characters
     */
    FIXED_ALPHA,
    /**
     * Variable-length numeric field with a 2-digit length prefix.
     *
     * Example:
     * DE 2 - Primary Account Number (PAN)
     */
    LLVAR_NUMERIC,
    /**
     * Variable-length alphanumeric/text field with a
     * 2-digit length prefix.
     *
     * Example:
     * DE 35 - Track 2 Data
     */
    LLVAR_ALPHA,
    /**
     * Variable-length alphanumeric/text field with a
     * 3-digit length prefix.
     *
     * Example:
     * DE 48 - Additional Data
     */
    LLLVAR_ALPHA,
    /**
     * Fixed-length binary data.
     *
     * Examples:
     * PIN block
     * MAC
     * Binary bitmap
     */
    BINARY_FIXED,
}