package com.saumay.payments.matching;

/**
 * Why a line or group needs a person (FRD-06). {@code DUPLICATE_SUSPECT} is reserved for Phase 4: until then a
 * duplicated reference is reported as {@link #AMBIGUOUS_TIE}, which is what ADR-0003 requires of any tie.
 */
public enum ExceptionKind {
    MISSING_LEDGER,
    MISSING_STATEMENT,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    LOW_CONFIDENCE,
    AMBIGUOUS_TIE
}