package com.saumay.payments.matching;

/**
 * Every tunable matching number in one validated, immutable object (FRD-05 AC-05.8). No threshold, window or
 * weight appears as a literal anywhere else in the matching code. A changed configuration is a new instance;
 * versioning and audit of changes happen where it is stored (Phase 5).
 */
public record MatchingConfig(
        int autoThresholdMilli,
        int reviewThresholdMilli,
        int dateWindowBusinessDays,
        int lookbackBusinessDays,
        int maxOneToManyCandidates,
        int fuzzyMinSimilarityMilli,
        int fuzzyCounterpartyWeightMilli) {

    public static final int MAX_DATE_WINDOW = 30;
    public static final int MAX_LOOKBACK = 60;
    public static final int MIN_CANDIDATES = 2;
    public static final int MAX_CANDIDATES = 12;

    public MatchingConfig {
        if (reviewThresholdMilli <= 0 || reviewThresholdMilli >= autoThresholdMilli
                || autoThresholdMilli > MatchScore.SCALE) {
            throw new IllegalArgumentException(
                    "Thresholds must satisfy 0 < review < auto <= " + MatchScore.SCALE
                            + ", was review=" + reviewThresholdMilli + " auto=" + autoThresholdMilli);
        }
        if (dateWindowBusinessDays < 1 || dateWindowBusinessDays > MAX_DATE_WINDOW) {
            throw new IllegalArgumentException("Date window must be 1 to " + MAX_DATE_WINDOW + " business days");
        }
        if (lookbackBusinessDays < dateWindowBusinessDays || lookbackBusinessDays > MAX_LOOKBACK) {
            throw new IllegalArgumentException(
                    "Lookback must be at least the date window and at most " + MAX_LOOKBACK + " business days");
        }
        if (maxOneToManyCandidates < MIN_CANDIDATES || maxOneToManyCandidates > MAX_CANDIDATES) {
            throw new IllegalArgumentException(
                    "One-to-many candidate limit must be " + MIN_CANDIDATES + " to " + MAX_CANDIDATES);
        }
        if (fuzzyMinSimilarityMilli <= 0 || fuzzyMinSimilarityMilli >= MatchScore.SCALE) {
            throw new IllegalArgumentException("Fuzzy minimum similarity must be between 0 and 1 exclusive");
        }
        if (fuzzyCounterpartyWeightMilli < 0 || fuzzyCounterpartyWeightMilli > MatchScore.SCALE) {
            throw new IllegalArgumentException("Fuzzy counterparty weight must be between 0 and 1 inclusive");
        }
    }

    /** The BRD defaults: 0.95 auto, 0.70 review, 3 business days, 10-day lookback, 8 candidates, 0.60 / 0.6. */
    public static MatchingConfig defaults() {
        return new MatchingConfig(950, 700, 3, 10, 8, 600, 600);
    }

    /** A copy with new thresholds, validated like any other configuration (AC-05.7). */
    public MatchingConfig withThresholds(int newAutoMilli, int newReviewMilli) {
        return new MatchingConfig(newAutoMilli, newReviewMilli, dateWindowBusinessDays, lookbackBusinessDays,
                maxOneToManyCandidates, fuzzyMinSimilarityMilli, fuzzyCounterpartyWeightMilli);
    }

    /** The narrative share of the fuzzy similarity blend; the two weights always add up to 1. */
    public int fuzzyNarrativeWeightMilli() {
        return MatchScore.SCALE - fuzzyCounterpartyWeightMilli;
    }

    /** The single place a score becomes a band. */
    public MatchBand classify(MatchScore score) {
        if (score.milli() >= autoThresholdMilli) {
            return MatchBand.AUTO_CONFIRM;
        }
        if (score.milli() >= reviewThresholdMilli) {
            return MatchBand.REVIEW;
        }
        return MatchBand.EXCEPTION;
    }
}