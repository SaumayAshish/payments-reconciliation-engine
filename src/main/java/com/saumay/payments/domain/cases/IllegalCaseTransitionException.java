package com.saumay.payments.domain.cases;

/** A requested status change that FRD-07 does not allow (error code ILLEGAL_STATE_TRANSITION at the API). */
public final class IllegalCaseTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final CaseStatus from;
    private final CaseStatus to;

    public IllegalCaseTransitionException(CaseStatus from, CaseStatus to) {
        super("Illegal case transition " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public CaseStatus from() {
        return from;
    }

    public CaseStatus to() {
        return to;
    }
}