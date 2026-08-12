package com.example.iso8583.core;

public enum IsoFieldType {

    FIXED_NUMERIC,   // e.g. Amount (12 digits), STAN (6 digits), Processing Code (6 digits)

    FIXED_ALPHA,     // e.g. Response Code (2 chars), Terminal ID (8 chars), Merchant ID (15 chars)

    LLVAR_NUMERIC,   // 2-digit length indicator followed by numeric string (e.g. DE 2 PAN)

    LLVAR_ALPHA,     // 2-digit length indicator followed by alphanumeric string (e.g. DE 35 Track 2 data)

    LLLVAR_ALPHA,    // 3-digit length indicator followed by alphanumeric string (e.g. DE 48 Private Data)

    BINARY_FIXED     // Fixed byte array/hex string (e.g. PIN Block, MAC)
}
