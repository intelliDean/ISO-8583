package com.dean.iso8583.core.clearing.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.dean.iso8583.core.clearing.utils.ClearingUtils.*;

/**
 * Developer Note:
 * Interchange Fee & Scheme Assessment Calculator.
 *
 * <h2>Interchange Mechanics</h2>
 * Interchange is the fee paid by the Acquirer (merchant bank) to the Issuer (cardholder bank)
 * to balance the costs of credit risk, fraud protection, and card rewards.
 *
 * <h2>Standard Scheme Rates (Configurable Defaults)</h2>
 * <ul>
 *   <li><b>Standard Credit</b>: 1.50% + $0.10</li>
 *   <li><b>Regulated Debit (Durbin Amendment)</b>: 0.05% + $0.21</li>
 *   <li><b>International / Cross-Border</b>: 2.00% + $0.20</li>
 * </ul>
 */
public final class InterchangeFeeCalculator {



    private InterchangeFeeCalculator() {
        // Utility class
    }

    /**
     * Calculates the interchange fee in cents and formats it as a 12-digit ISO 8583 numeric string.
     *
     * @param amountIso 12-digit ISO 8583 transaction amount (e.g. "000000002550" = $25.50)
     * @return 12-digit ISO 8583 interchange fee string (e.g. "000000000048" = $0.48)
     */
    public static String calculateFee(String amountIso) {
        return calculateFee(amountIso, DEFAULT_PERCENTAGE_RATE, DEFAULT_FIXED_FEE);
    }

    public static String calculateFee(String amountIso, BigDecimal percentageRate, BigDecimal fixedFeeDollars) {

        if (isBlankAmount(amountIso)) return ZERO_FEE_ISO;

        try {
            BigDecimal dollars = centsIsoToDollars(amountIso);
            long feeCents = computeFeeCents(dollars, percentageRate, fixedFeeDollars);
            return formatCentsIso(feeCents);
        } catch (NumberFormatException e) {
            return ZERO_FEE_ISO;
        }
    }

    private static boolean isBlankAmount(String amountIso) {
        return amountIso == null || amountIso.isBlank();
    }

    private static BigDecimal centsIsoToDollars(String amountIso) {
        long rawCents = Long.parseLong(amountIso.trim());
        return BigDecimal.valueOf(rawCents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static long computeFeeCents(BigDecimal dollars, BigDecimal percentageRate, BigDecimal fixedFeeDollars) {
        BigDecimal variableFee = dollars.multiply(percentageRate);
        BigDecimal totalFeeDollars = variableFee.add(fixedFeeDollars);
        long feeCents = totalFeeDollars.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
        return Math.max(0, feeCents);
    }

    private static String formatCentsIso(long cents) {
        return "%012d".formatted(cents);
    }
}
