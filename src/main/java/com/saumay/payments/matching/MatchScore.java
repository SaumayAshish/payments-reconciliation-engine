package com.saumay.payments.matching;

import java.util.Locale;

/**
 * A match confidence as an integer number of thousandths, 0 to 1000 (ADR-0003). Integers keep every threshold
 * comparison exact: 949 is below 950, with no rounding question.
 */
public record MatchScore(int milli) implements Comparable<MatchScore> {

    public static final int SCALE = 1000;

    public MatchScore {
        if (milli < 0 || milli > SCALE) {
            throw new IllegalArgumentException("Score must be between 0 and " + SCALE + " thousandths, was " + milli);
        }
    }

    public static MatchScore ofMilli(int milli) {
        return new MatchScore(milli);
    }

    @Override
    public int compareTo(MatchScore other) {
        return Integer.compare(milli, other.milli);
    }

    /** Renders as a three-decimal fraction, for example 0.950. Used in reasons and the canonical output. */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%d.%03d", milli / SCALE, milli % SCALE);
    }
}