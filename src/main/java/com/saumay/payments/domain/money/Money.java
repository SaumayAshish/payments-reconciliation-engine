package com.saumay.payments.domain.money;

import java.util.Objects;

/**
 * An immutable amount of money: a signed count of minor units plus a currency (ADR-0001).
 *
 * <p>Sign convention (FRD Q-01): money in is positive, money out is negative, from the account owner's view.
 *
 * <p>Invariants: the currency is present, and {@code Long.MIN_VALUE} is not a valid amount because it has no
 * positive counterpart, which would make {@link #negate()} and {@link #abs()} partial. Arithmetic is exact and
 * throws {@link ArithmeticException} on overflow. Mixing currencies throws {@link CurrencyMismatchException}.
 * Zero is a valid amount here (a sum can be zero); rejecting zero lines is an ingestion rule.
 */
public record Money(long minorUnits, CurrencyCode currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (minorUnits == Long.MIN_VALUE) {
            throw new IllegalArgumentException("Long.MIN_VALUE is not a valid amount of minor units");
        }
    }

    public static Money of(long minorUnits, CurrencyCode currency) {
        return new Money(minorUnits, currency);
    }

    public static Money zero(CurrencyCode currency) {
        return new Money(0L, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(-minorUnits, currency);
    }

    public Money abs() {
        return minorUnits < 0 ? negate() : this;
    }

    public int signum() {
        return Long.signum(minorUnits);
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    /** Diagnostic form such as {@code 125050 GBP}. Decimal display formatting is the API layer's job. */
    @Override
    public String toString() {
        return minorUnits + " " + currency;
    }
}