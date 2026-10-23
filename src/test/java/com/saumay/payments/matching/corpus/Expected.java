package com.saumay.payments.matching.corpus;

/** What the engine must do with a truth case. Auto outcomes are the only ones that confirm without a human. */
public enum Expected {
    AUTO_EXACT,
    AUTO_TOLERANCE,
    REVIEW_TOLERANCE,
    REVIEW_FUZZY,
    REVIEW_ONE_TO_MANY,
    /** Every line of the case sits in one ambiguous group and nothing is confirmed. */
    AMBIGUOUS,
    /** No line of the case is matched or held in a group. */
    UNMATCHED;

    public boolean isAuto() {
        return this == AUTO_EXACT || this == AUTO_TOLERANCE;
    }

    public boolean isMatch() {
        return this != AMBIGUOUS && this != UNMATCHED;
    }
}