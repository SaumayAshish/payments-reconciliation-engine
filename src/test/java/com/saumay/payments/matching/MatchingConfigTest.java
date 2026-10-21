package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class MatchingConfigTest {

    private static final MatchingConfig DEFAULTS = MatchingConfig.defaults();

    @Test
    void defaultsMatchTheBrd() {
        assertEquals(950, DEFAULTS.autoThresholdMilli());
        assertEquals(700, DEFAULTS.reviewThresholdMilli());
        assertEquals(3, DEFAULTS.dateWindowBusinessDays());
        assertEquals(10, DEFAULTS.lookbackBusinessDays());
        assertEquals(8, DEFAULTS.maxOneToManyCandidates());
        assertEquals(600, DEFAULTS.fuzzyMinSimilarityMilli());
        assertEquals(600, DEFAULTS.fuzzyCounterpartyWeightMilli());
        assertEquals(400, DEFAULTS.fuzzyNarrativeWeightMilli());
    }

    @Test
    void bandBoundariesAreExact() {
        assertEquals(MatchBand.AUTO_CONFIRM, DEFAULTS.classify(MatchScore.ofMilli(1000)));
        assertEquals(MatchBand.AUTO_CONFIRM, DEFAULTS.classify(MatchScore.ofMilli(950)));
        assertEquals(MatchBand.REVIEW, DEFAULTS.classify(MatchScore.ofMilli(949)));
        assertEquals(MatchBand.REVIEW, DEFAULTS.classify(MatchScore.ofMilli(700)));
        assertEquals(MatchBand.EXCEPTION, DEFAULTS.classify(MatchScore.ofMilli(699)));
        assertEquals(MatchBand.EXCEPTION, DEFAULTS.classify(MatchScore.ofMilli(0)));
    }

    @Test
    void thresholdChangeMovesTheBoundary() {
        MatchingConfig lowered = DEFAULTS.withThresholds(900, 600);
        assertEquals(MatchBand.AUTO_CONFIRM, lowered.classify(MatchScore.ofMilli(900)));
        assertEquals(MatchBand.REVIEW, lowered.classify(MatchScore.ofMilli(899)));
        assertEquals(MatchBand.EXCEPTION, lowered.classify(MatchScore.ofMilli(599)));
        assertEquals(950, DEFAULTS.autoThresholdMilli(), "the original configuration is unchanged");
    }

    @Test
    void rejectsInvalidThresholds() {
        assertThrows(IllegalArgumentException.class, () -> DEFAULTS.withThresholds(700, 700));
        assertThrows(IllegalArgumentException.class, () -> DEFAULTS.withThresholds(600, 700));
        assertThrows(IllegalArgumentException.class, () -> DEFAULTS.withThresholds(1001, 700));
        assertThrows(IllegalArgumentException.class, () -> DEFAULTS.withThresholds(950, 0));
        assertThrows(IllegalArgumentException.class, () -> DEFAULTS.withThresholds(950, -5));
    }

    @Test
    void rejectsInvalidWindowAndLookback() {
        assertThrows(IllegalArgumentException.class, () -> new MatchingConfig(950, 700, 0, 10, 8, 600, 600));
        assertThrows(IllegalArgumentException.class, () -> new MatchingConfig(950, 700, 31, 60, 8, 600, 600));
        assertThrows(IllegalArgumentException.class, () -> new MatchingConfig(950, 700, 5, 4, 8, 600, 600));
        assertThrows(IllegalArgumentException.class, () -> new MatchingConfig(950, 700, 3, 61, 8, 600, 600));
    }

    @Test
    void rejectsInvalidCandidateLimit() {
        assertThrows(IllegalArgumentException.class, () -> new MatchingConfig(950, 700, 3, 10, 1, 600, 600));
        assertThrows(IllegalArgumentException.class, () -> new MatchingConfig(950, 700, 3, 10, 13, 600, 600));
        assertEquals(12, new MatchingConfig(950, 700, 3, 10, 12, 600, 600).maxOneToManyCandidates());
    }

    @Test
    void rejectsInvalidFuzzySettings() {
        assertThrows(IllegalArgumentException.class, () -> new MatchingConfig(950, 700, 3, 10, 8, 0, 600));
        assertThrows(IllegalArgumentException.class, () -> new MatchingConfig(950, 700, 3, 10, 8, 1000, 600));
        assertThrows(IllegalArgumentException.class, () -> new MatchingConfig(950, 700, 3, 10, 8, 600, -1));
        assertThrows(IllegalArgumentException.class, () -> new MatchingConfig(950, 700, 3, 10, 8, 600, 1001));
    }

    @Test
    void bandNeverDecreasesAsTheScoreRises() {
        SplittableRandom random = new SplittableRandom(20261021L);
        for (int trial = 0; trial < 500; trial++) {
            int auto = 2 + random.nextInt(999);
            int review = 1 + random.nextInt(auto - 1);
            MatchingConfig config = DEFAULTS.withThresholds(auto, review);
            MatchBand previous = MatchBand.EXCEPTION;
            for (int milli = 0; milli <= MatchScore.SCALE; milli++) {
                MatchBand band = config.classify(MatchScore.ofMilli(milli));
                assertTrue(band.compareTo(previous) >= 0,
                        "band fell at score " + milli + " with auto=" + auto + " review=" + review);
                previous = band;
            }
        }
    }
}