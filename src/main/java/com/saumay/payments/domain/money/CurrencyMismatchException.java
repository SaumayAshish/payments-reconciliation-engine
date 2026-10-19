package com.saumay.payments.domain.money;

/** Thrown when arithmetic or comparison is attempted across two different currencies. There is no implicit FX. */
public final class CurrencyMismatchException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public CurrencyMismatchException(CurrencyCode left, CurrencyCode right) {
        super("Currency mismatch: " + left + " vs " + right);
    }
}