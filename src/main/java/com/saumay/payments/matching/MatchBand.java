package com.saumay.payments.matching;

/** What happens to a scored candidate (FRD-05). Declared in increasing order of confidence. */
public enum MatchBand {
    /** Below the review threshold: no usable match, the line becomes a break. */
    EXCEPTION,
    /** Plausible but not certain: a human reviews it with the top candidates. */
    REVIEW,
    /** At or above the auto threshold: confirmed without review. */
    AUTO_CONFIRM
}