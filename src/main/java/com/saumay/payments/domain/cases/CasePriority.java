package com.saumay.payments.domain.cases;

/** Case priority (FRD-06). P1 is the most urgent. */
public enum CasePriority {
    P1,
    P2,
    P3,
    P4;

    /** One level more urgent; P1 stays P1. Used when an escalation raises a case (ADR-0008). */
    public CasePriority raisedOneLevel() {
        return this == P1 ? P1 : values()[ordinal() - 1];
    }
}