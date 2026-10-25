package com.saumay.payments.domain.cases;

/**
 * Why a case exists (FRD-06). Names match the engine's exception kinds so intake can map them by name, plus
 * {@code DUPLICATE_SUSPECT}, which is routed but not yet detected (ADR-0008).
 */
public enum CaseKind {
    MISSING_LEDGER,
    MISSING_STATEMENT,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    DUPLICATE_SUSPECT,
    LOW_CONFIDENCE,
    AMBIGUOUS_TIE;

    /** A low-confidence match or an unbreakable tie is reviewed; everything else is a break (ADR-0007). */
    public CaseClass caseClass() {
        return this == LOW_CONFIDENCE || this == AMBIGUOUS_TIE ? CaseClass.REVIEW : CaseClass.BREAK;
    }
}