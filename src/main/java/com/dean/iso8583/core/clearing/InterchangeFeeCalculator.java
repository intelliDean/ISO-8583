package com.dean.iso8583.core.clearing;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

    private static final BigDecimal DEFAULT_PERCENTAGE_RATE = new BigDecimal("0.0150"); // 1.50%
    private static final BigDecimal DEFAULT_FIXED_FEE = new BigDecimal("0.10");         // $0.10

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

    /**
     * Calculates the interchange fee given custom percentage and fixed fee rates.
     *
     * @param amountIso       12-digit ISO 8583 transaction amount
     * @param percentageRate  e.g. 0.0150 for 1.50%
     * @param fixedFeeDollars e.g. 0.10 for $0.10
     * @return 12-digit ISO 8583 interchange fee string
     */
    public static String calculateFee(String amountIso, BigDecimal percentageRate, BigDecimal fixedFeeDollars) {
        if (amountIso == null || amountIso.isBlank()) {
            return "000000000000";
        }

        try {
            long rawCents = Long.parseLong(amountIso.trim());
            BigDecimal dollars = BigDecimal.valueOf(rawCents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            BigDecimal variableFee = dollars.multiply(percentageRate);
            BigDecimal totalFeeDollars = variableFee.add(fixedFeeDollars);

            long feeCents = totalFeeDollars.multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();

            if (feeCents < 0) feeCents = 0;

            return String.format("%012d", feeCents);
        } catch (NumberFormatException e) {
            return "000000000000";
        }
    }
}
