package com.dean.iso8583.core.clearing.utils;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

public final class ClearingUtils {


    private ClearingUtils() {}

    public static final BigDecimal DEFAULT_PERCENTAGE_RATE = new BigDecimal("0.0150"); // 1.50%
    public static final BigDecimal DEFAULT_FIXED_FEE = new BigDecimal("0.10");         // $0.10

    /**
     * Calculates the interchange fee given custom percentage and fixed fee rates.
     *
     * @param amountIso       12-digit ISO 8583 transaction amount
     * @param percentageRate  e.g. 0.0150 for 1.50%
     * @param fixedFeeDollars e.g. 0.10 for $0.10
     * @return 12-digit ISO 8583 interchange fee string
     */
    public static final String ZERO_FEE_ISO = "000000000000";


    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    public static final String DEFAULT_NETWORK_ID = "MASTERCARD-IPM";
    public static final String DEFAULT_LOCK_NETWORK = "DEFAULT";
    public static final long LOCK_WAIT_MILLIS = 5_000;
    public static final long WAIT_TIMEOUT = 3_000;
    public static final long LOCK_LEASE_MILLIS = 10_000;
    public static final String CURRENCY_CODE_DEFAULT = "840";
    public static final String DEFAULT_DISPUTE_CODE = "4837";
    public static final String MTI_I44O = "1440";
    public static final String MTI_I240 = "1240";
    public static final String TPDU = "6000000000";
    public static final String DEFAULT_PROCESSING_CODE = "000000";
    public static final String AGGREGATE_TYPE_CLEARING_BATCH = "CLEARING_BATCH";
    public static final String AGGREGATE_TYPE_CHARGEBACK = "CHARGEBACK";
    public static final String NETWORK_ID_IMPORTED = "IMPORTED";
    public static final String RRN = "123456789012";
    public static final String AUTH_CODE = "AUTH01";
}
