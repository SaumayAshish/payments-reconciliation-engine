package com.saumay.payments.matching.corpus;

/** The generator rule that produced a truth case (docs/synthetic-data-spec.md sections 4 and 5). */
public enum Scenario {
    EXACT,
    TOLERANCE_1D,
    TOLERANCE_2D_3D,
    FUZZY,
    ONE_TO_MANY,
    NO_LEDGER,
    NO_STATEMENT,
    SAME_AMOUNT_DIFFERENT_REF,
    SAME_REF_DIFFERENT_AMOUNT,
    SAME_REF_DIFFERENT_CURRENCY,
    OUT_OF_WINDOW,
    REVERSAL_PAIR,
    REVERSAL_BOTH_SIDES,
    DUPLICATE_REFERENCE,
    IDENTICAL_TWINS,
    FUZZY_NEAR_MISS,
    SUBSET_AMBIGUITY,
    CROSS_ACCOUNT,
    WEEKEND_DATE;

    public boolean isHardNegative() {
        return ordinal() >= SAME_AMOUNT_DIFFERENT_REF.ordinal();
    }
}