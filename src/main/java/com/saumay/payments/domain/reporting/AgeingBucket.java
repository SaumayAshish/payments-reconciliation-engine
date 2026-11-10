package com.saumay.payments.domain.reporting;

/** Non-overlapping business-day ageing buckets (FRD-11, Q-06). */
public enum AgeingBucket {
    ZERO_TO_ONE,
    TWO_TO_FIVE,
    SIX_TO_TEN,
    OVER_TEN;

    public static AgeingBucket forAge(int businessDays) {
        if (businessDays < 0) {
            throw new IllegalArgumentException("businessDays must not be negative");
        }
        if (businessDays <= 1) {
            return ZERO_TO_ONE;
        }
        if (businessDays <= 5) {
            return TWO_TO_FIVE;
        }
        if (businessDays <= 10) {
            return SIX_TO_TEN;
        }
        return OVER_TEN;
    }
}