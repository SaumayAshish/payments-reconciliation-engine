package com.saumay.payments.ingestion.normalise;

import com.saumay.payments.domain.money.CurrencyCode;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Converts decimal text to exact minor units (ADR-0001). The only place in the system where {@link BigDecimal}
 * is permitted: money enters as text and leaves as a {@code long}.
 *
 * <p>Accepted: an optional sign, digits, and an optional fraction ({@code -1250.50}). Rejected: scientific
 * notation, thousands separators, blanks. Digits beyond the currency's exponent are rejected, never rounded
 * (FRD AC-02.3); trailing zeros that need no rounding are fine ({@code 1500.00} for JPY is {@code 1500}).
 */
public final class AmountParser {

    private static final Pattern PLAIN_DECIMAL = Pattern.compile("[+-]?[0-9]+(\\.[0-9]+)?");
    private static final int MAX_LENGTH = 40;

    private AmountParser() {
    }

    public static long parseMinorUnits(String text, CurrencyCode currency) {
        if (text == null) {
            throw new LineValidationException(LineErrorCode.INVALID_AMOUNT, "amount is missing");
        }
        String trimmed = text.strip();
        if (trimmed.length() > MAX_LENGTH || !PLAIN_DECIMAL.matcher(trimmed).matches()) {
            throw new LineValidationException(LineErrorCode.INVALID_AMOUNT, "amount is not a plain decimal number");
        }
        BigDecimal value = new BigDecimal(trimmed);
        int exponent = currency.exponent();
        if (value.stripTrailingZeros().scale() > exponent) {
            throw new LineValidationException(LineErrorCode.AMOUNT_PRECISION,
                    "amount has more decimal places than " + currency + " allows (" + exponent + ")");
        }
        final long minorUnits;
        try {
            minorUnits = value.movePointRight(exponent).longValueExact();
        } catch (ArithmeticException e) {
            throw new LineValidationException(LineErrorCode.AMOUNT_RANGE, "amount is outside the supported range");
        }
        if (minorUnits == Long.MIN_VALUE) {
            throw new LineValidationException(LineErrorCode.AMOUNT_RANGE, "amount is outside the supported range");
        }
        return minorUnits;
    }
}