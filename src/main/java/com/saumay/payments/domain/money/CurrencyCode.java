package com.saumay.payments.domain.money;

import java.util.Currency;
import java.util.regex.Pattern;

/**
 * An ISO 4217 alpha-3 currency code such as {@code GBP}.
 *
 * <p>Pseudo-currencies without a minor-unit exponent (for example {@code XXX}) are rejected, because every
 * amount in this system is an integer count of minor units and needs a defined exponent (ADR-0001).
 */
public record CurrencyCode(String code) implements Comparable<CurrencyCode> {

    private static final Pattern FORMAT = Pattern.compile("[A-Z]{3}");

    public CurrencyCode {
        if (code == null || !FORMAT.matcher(code).matches()) {
            throw new IllegalArgumentException("Currency code must be three upper-case letters");
        }
        if (fractionDigits(code) < 0) {
            throw new IllegalArgumentException("Currency has no minor-unit exponent: " + code);
        }
    }

    private static int fractionDigits(String code) {
        try {
            return Currency.getInstance(code).getDefaultFractionDigits();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown ISO 4217 currency code: " + code, e);
        }
    }

    public static CurrencyCode of(String code) {
        return new CurrencyCode(code);
    }

    /** Number of decimal digits in the minor unit: GBP 2, JPY 0, KWD 3. */
    public int exponent() {
        return fractionDigits(code);
    }

    @Override
    public int compareTo(CurrencyCode other) {
        return code.compareTo(other.code);
    }

    @Override
    public String toString() {
        return code;
    }
}