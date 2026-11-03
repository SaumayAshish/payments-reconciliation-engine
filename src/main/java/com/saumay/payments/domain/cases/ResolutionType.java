package com.saumay.payments.domain.cases;

/** How a case was resolved. Write-off and adjustment are controlled actions (FRD-08). */
public enum ResolutionType {
    MANUAL_MATCH,
    SPLIT,
    WRITE_OFF,
    ADJUSTMENT
}