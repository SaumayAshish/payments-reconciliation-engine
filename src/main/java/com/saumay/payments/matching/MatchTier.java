package com.saumay.payments.matching;

/**
 * The four matching tiers (FRD-04) with the score range each may produce. Tiers 3 and 4 are capped at 0.949 so
 * they cannot auto-confirm under the default thresholds (Q-03).
 */
public enum MatchTier {
    EXACT(1, 1000, 1000),
    TOLERANCE(2, 900, 990),
    FUZZY(3, 700, 949),
    ONE_TO_MANY(4, 800, 949);

    private final int number;
    private final int floorMilli;
    private final int ceilingMilli;

    MatchTier(int number, int floorMilli, int ceilingMilli) {
        this.number = number;
        this.floorMilli = floorMilli;
        this.ceilingMilli = ceilingMilli;
    }

    public int number() {
        return number;
    }

    public int floorMilli() {
        return floorMilli;
    }

    public int ceilingMilli() {
        return ceilingMilli;
    }

    public boolean allows(MatchScore score) {
        return score.milli() >= floorMilli && score.milli() <= ceilingMilli;
    }
}