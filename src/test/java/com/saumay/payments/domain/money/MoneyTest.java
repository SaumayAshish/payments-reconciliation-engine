package com.saumay.payments.domain.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final CurrencyCode JPY = CurrencyCode.of("JPY");

    @Test
    void rejectsNullCurrency() {
        assertThrows(NullPointerException.class, () -> Money.of(1, null));
    }

    @Test
    void rejectsLongMinValueBecauseItHasNoPositiveCounterpart() {
        assertThrows(IllegalArgumentException.class, () -> Money.of(Long.MIN_VALUE, GBP));
    }

    @Test
    void addingTenPenniesTenTimesIsExactlyOnePound() {
        // The classic floating-point trap: ten additions of 0.10 do not give 1.00 in a double.
        Money total = Money.zero(GBP);
        for (int i = 0; i < 10; i++) {
            total = total.plus(Money.of(10, GBP));
        }
        assertEquals(Money.of(100, GBP), total);
    }

    @Test
    void overflowOnAddOrSubtractThrowsInsteadOfWrapping() {
        assertThrows(ArithmeticException.class, () -> Money.of(Long.MAX_VALUE, GBP).plus(Money.of(1, GBP)));
        assertThrows(ArithmeticException.class, () -> Money.of(-Long.MAX_VALUE, GBP).minus(Money.of(2, GBP)));
    }

    @Test
    void aResultOfLongMinValueIsRejectedBecauseItIsNotAValidAmount() {
        // -MAX - 1 fits in a long, but Money forbids Long.MIN_VALUE so negate() and abs() stay total.
        assertThrows(IllegalArgumentException.class, () -> Money.of(-Long.MAX_VALUE, GBP).minus(Money.of(1, GBP)));
        assertThrows(IllegalArgumentException.class, () -> Money.of(Long.MAX_VALUE, GBP).negate().minus(Money.of(1, GBP)));
    }

    @Test
    void arithmeticAcrossCurrenciesIsRejected() {
        assertThrows(CurrencyMismatchException.class, () -> Money.of(1, GBP).plus(Money.of(1, JPY)));
        assertThrows(CurrencyMismatchException.class, () -> Money.of(1, GBP).minus(Money.of(1, JPY)));
        assertThrows(CurrencyMismatchException.class, () -> Money.of(1, GBP).compareTo(Money.of(1, JPY)));
    }

    @Test
    void moneyInDifferentCurrenciesIsNeverEqual() {
        assertFalse(Money.of(100, GBP).equals(Money.of(100, JPY)));
    }

    @Test
    void negateAndAbsBehaveForBothSigns() {
        assertEquals(Money.of(-5, GBP), Money.of(5, GBP).negate());
        assertEquals(Money.of(5, GBP), Money.of(-5, GBP).abs());
        assertEquals(Money.of(5, GBP), Money.of(5, GBP).abs());
        assertEquals(Money.of(Long.MAX_VALUE, GBP), Money.of(-Long.MAX_VALUE, GBP).abs());
    }

    @Test
    void signPredicatesAgree() {
        assertTrue(Money.of(1, GBP).isPositive());
        assertTrue(Money.of(-1, GBP).isNegative());
        assertTrue(Money.zero(GBP).isZero());
        assertEquals(0, Money.zero(GBP).signum());
    }

    @Test
    void diagnosticFormIsMinorUnitsAndCurrency() {
        assertEquals("125050 GBP", Money.of(125050, GBP).toString());
    }
}