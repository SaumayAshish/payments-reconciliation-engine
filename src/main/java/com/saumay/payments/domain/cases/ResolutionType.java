package com.saumay.payments.domain.cases;

/** How a case was resolved. Adjustment and write-off join in Phase 5 with maker-checker. */
public enum ResolutionType {
    MANUAL_MATCH,
    SPLIT
}