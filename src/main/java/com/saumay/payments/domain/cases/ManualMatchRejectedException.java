package com.saumay.payments.domain.cases;

/** A proposed manual match breaks a rule. The message is safe to show to a client. */
public final class ManualMatchRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ManualMatchRejectedException(String message) {
        super(message);
    }
}