package com.dean.iso8583.core;

import com.dean.iso8583.core.dto.IsoFieldDef;
import com.dean.iso8583.core.dto.IsoFieldType;

import java.util.HashMap;
import java.util.Map;

public class IsoSpec {

    private static final Map<Integer, IsoFieldDef> FIELD_DEFS;

    static {
        Map<Integer, IsoFieldDef> fields = new HashMap<>();

        // Bitmaps
        fields.put(1, IsoFieldDef.builder() //decided to use builder
                .fieldId(1)
                .name("Secondary Bitmap")
                .type(IsoFieldType.BINARY_FIXED)
                .maxLength(8)
                .description("Indicates presence of DE 65-128")
                .build()
        );

        // Data Elements 2 - 128
        fields.put(2, new IsoFieldDef(
                2,
                "Primary Account Number (PAN)",
                IsoFieldType.LLVAR_NUMERIC,
                19,
                "Card Number"));

        fields.put(3, new IsoFieldDef(
                3,
                "Processing Code",
                IsoFieldType.FIXED_NUMERIC,
                6,
                "Transaction type code (e.g. 000000 = Purchase)")
        );

        fields.put(4, new IsoFieldDef(
                4,
                "Amount, Transaction",
                IsoFieldType.FIXED_NUMERIC,
                12,
                "Transaction amount in minor units (e.g. cents)")
        );

        fields.put(7, new IsoFieldDef(
                7,
                "Transmission Date & Time",
                IsoFieldType.FIXED_NUMERIC,
                10,
                "MMDDhhmmss format")
        );

        fields.put(11, new IsoFieldDef(
                11,
                "Systems Trace Audit Number (STAN)",
                IsoFieldType.FIXED_NUMERIC,
                6,
                "Unique sequence number for transaction tracing")
        );

        fields.put(12, new IsoFieldDef(
                12,
                "Time, Local Transaction",
                IsoFieldType.FIXED_NUMERIC,
                6,
                "hhmmss format")
        );

        fields.put(13, new IsoFieldDef(
                13,
                "Date, Local Transaction",
                IsoFieldType.FIXED_NUMERIC,
                4,
                "MMDD format")
        );

        fields.put(14, new IsoFieldDef(
                14,
                "Date, Expiration",
                IsoFieldType.FIXED_NUMERIC,
                4,
                "YYMM format")
        );

        fields.put(22, new IsoFieldDef(
                22,
                "Point of Service Entry Mode",
                IsoFieldType.FIXED_NUMERIC,
                3,
                "POS entry mode (e.g. 021 = Chip/PAN)")
        );

        fields.put(32, new IsoFieldDef(
                32,
                "Acquiring Institution ID",
                IsoFieldType.LLVAR_NUMERIC,
                11,
                "Acquirer Identification Code")
        );

        fields.put(35, new IsoFieldDef(
                35,
                "Track 2 Data",
                IsoFieldType.LLVAR_ALPHA,
                37,
                "Magstripe Track 2 data")
        );

        fields.put(37, new IsoFieldDef(
                37,
                "Retrieval Reference Number (RRN)",
                IsoFieldType.FIXED_ALPHA,
                12,
                "Unique reference number")
        );

        fields.put(38, new IsoFieldDef(
                38,
                "Authorization Identification Response",
                IsoFieldType.FIXED_ALPHA,
                6,
                "Approval Code from Issuer")
        );

        fields.put(39, new IsoFieldDef(
                39,
                "Response Code",
                IsoFieldType.FIXED_ALPHA,
                2,
                "Action Code (00 = Approved, 51 = Insufficient Funds, etc.)")
        );

        fields.put(41, new IsoFieldDef(
                41,
                "Card Acceptor Terminal ID (CATID)",
                IsoFieldType.FIXED_ALPHA,
                8,
                "Terminal Identification")
        );

        fields.put(42, new IsoFieldDef(
                42,
                "Card Acceptor ID (CAID)",
                IsoFieldType.FIXED_ALPHA,
                15,
                "Merchant Identification")
        );

        fields.put(43, new IsoFieldDef(
                43,
                "Card Acceptor Name/Location",
                IsoFieldType.FIXED_ALPHA,
                40,
                "Merchant Name & Address")
        );

        fields.put(48, new IsoFieldDef(
                48,
                "Private Data",
                IsoFieldType.LLLVAR_ALPHA,
                999,
                "Custom network/issuer specific private data")
        );

        fields.put(49, new IsoFieldDef(
                49,
                "Currency Code, Transaction",
                IsoFieldType.FIXED_NUMERIC,
                3,
                "ISO 4217 numeric currency code (e.g. 840 = USD)")
        );

        fields.put(52, new IsoFieldDef(
                52,

                "Personal Identification Number (PIN) Data",
                IsoFieldType.BINARY_FIXED,
                8,
                "Encrypted PIN Block")
        );

        fields.put(70, new IsoFieldDef(
                70,
                "Network Management Information Code", IsoFieldType.FIXED_NUMERIC,
                3,
                "NMIC (e.g. 001 = Logon, 301 = Echo Test)")
        );

        fields.put(90, new IsoFieldDef(
                90,
                "Original Data Elements",
                IsoFieldType.FIXED_NUMERIC,
                42,
                "Used for Reversals (Original MTI, STAN, RRN, etc.)")
        );

        fields.put(102, new IsoFieldDef(
                102,
                "Account Identification 1",
                IsoFieldType.LLVAR_ALPHA,
                28,
                "Source Account")
        );

        fields.put(103, new IsoFieldDef(
                103,
                "Account Identification 2",
                IsoFieldType.LLVAR_ALPHA,
                28,
                "Destination Account"));

        FIELD_DEFS = Map.copyOf(fields);
    }

    public static IsoFieldDef getFieldDef(int fieldId) {
        return FIELD_DEFS.get(fieldId);
    }

    public static Map<Integer, IsoFieldDef> getAllFieldDefs() {
        return FIELD_DEFS;
    }
}

