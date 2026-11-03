package com.saumay.payments.domain.approval;

/** The person who made a controlled request tried to decide it. Carries no names: the message is safe to show. */
public final class SeparationOfDutiesException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SeparationOfDutiesException() {
        super("the maker of a controlled action cannot be its checker");
    }
}