package com.saumay.payments.ingestion.normalise;

/** Why a raw line was rejected. Rejected lines are stored and queryable, never silently dropped (FRD-02). */
public enum LineErrorCode {
    INVALID_ACCOUNT,
    INVALID_CURRENCY,
    INVALID_DATE,
    INVALID_AMOUNT,
    AMOUNT_PRECISION,
    AMOUNT_RANGE,
    AMOUNT_ZERO,
    MISSING_REFERENCE,
    NARRATIVE_TOO_LONG
}