package com.saumay.payments.domain.cases;

/** One queue serves both kinds of work (A-02): items a person must confirm, and breaks with no usable match. */
public enum CaseClass {
    REVIEW,
    BREAK
}