package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MatchTierTest {

    @Test
    void tiersAreNumberedInPipelineOrder() {
        assertEquals(1, MatchTier.EXACT.number());
        assertEquals(2, MatchTier.TOLERANCE.number());
        assertEquals(3, MatchTier.FUZZY.number());
        assertEquals(4, MatchTier.ONE_TO_MANY.number());
    }

    @Test
    void fuzzyAndBatchTiersCannotReachTheDefaultAutoThreshold() {
        int auto = MatchingConfig.defaults().autoThresholdMilli();
        assertTrue(MatchTier.FUZZY.ceilingMilli() < auto);
        assertTrue(MatchTier.ONE_TO_MANY.ceilingMilli() < auto);
    }

    @Test
    void exactAndToleranceCanAutoConfirmByDefault() {
        MatchingConfig config = MatchingConfig.defaults();
        assertEquals(MatchBand.AUTO_CONFIRM, config.classify(MatchScore.ofMilli(MatchTier.EXACT.ceilingMilli())));
        assertEquals(MatchBand.AUTO_CONFIRM, config.classify(MatchScore.ofMilli(960)));
        assertEquals(MatchBand.REVIEW, config.classify(MatchScore.ofMilli(930)));
    }

    @Test
    void allowsOnlyScoresInsideItsRange() {
        assertTrue(MatchTier.TOLERANCE.allows(MatchScore.ofMilli(900)));
        assertTrue(MatchTier.TOLERANCE.allows(MatchScore.ofMilli(990)));
        assertFalse(MatchTier.TOLERANCE.allows(MatchScore.ofMilli(899)));
        assertFalse(MatchTier.TOLERANCE.allows(MatchScore.ofMilli(991)));
        assertTrue(MatchTier.EXACT.allows(MatchScore.ofMilli(1000)));
        assertFalse(MatchTier.EXACT.allows(MatchScore.ofMilli(999)));
        assertTrue(MatchTier.FUZZY.allows(MatchScore.ofMilli(949)));
        assertFalse(MatchTier.FUZZY.allows(MatchScore.ofMilli(950)));
        assertTrue(MatchTier.ONE_TO_MANY.allows(MatchScore.ofMilli(800)));
        assertFalse(MatchTier.ONE_TO_MANY.allows(MatchScore.ofMilli(799)));
    }
}