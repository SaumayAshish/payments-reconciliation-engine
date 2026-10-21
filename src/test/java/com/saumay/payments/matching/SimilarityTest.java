package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class SimilarityTest {

    @Test
    void tokenSetSimilarityIsTheJaccardRatioOfTheTokens() {
        assertEquals(1000, Similarity.tokenSetMilli("ACME TRADING", "ACME TRADING"));
        assertEquals(1000, Similarity.tokenSetMilli("ACME TRADING", "TRADING ACME"));
        assertEquals(500, Similarity.tokenSetMilli("ACME", "ACME TRADING"));
        assertEquals(333, Similarity.tokenSetMilli("ACME TRADING", "ACME TRADERS"));
        assertEquals(0, Similarity.tokenSetMilli("ACME", "BETA"));
    }

    @Test
    void aCounterpartyWithNoTokensIsNeverEvidence() {
        assertEquals(0, Similarity.tokenSetMilli("", "ACME"));
        assertEquals(0, Similarity.tokenSetMilli("ACME", ""));
        assertEquals(0, Similarity.tokenSetMilli("", ""));
    }

    @Test
    void editSimilarityIsOneMinusDistanceOverTheLongerLength() {
        assertEquals(1000, Similarity.editSimilarityMilli("INVOICE PAYMENT", "INVOICE PAYMENT"));
        assertEquals(833, Similarity.editSimilarityMilli("INVOICE 42 PAYMENT", "INVOICE 42 PYMT"));
        assertEquals(571, Similarity.editSimilarityMilli("KITTEN", "SITTING"));
        assertEquals(666, Similarity.editSimilarityMilli("ABC", "ABD"));
        assertEquals(0, Similarity.editSimilarityMilli("ABC", "XYZ"));
    }

    @Test
    void editSimilarityIgnoresCasePunctuationAndSpacing() {
        assertEquals(1000, Similarity.editSimilarityMilli("Invoice-Payment 42", "INVOICE PAYMENT 42"));
        assertEquals(1000, Similarity.editSimilarityMilli("  invoice   payment ", "INVOICE PAYMENT"));
    }

    @Test
    void emptyNarrativesAreNotEvidenceEither() {
        assertEquals(0, Similarity.editSimilarityMilli("", ""));
        assertEquals(0, Similarity.editSimilarityMilli("  --  ", ""));
        assertEquals(0, Similarity.editSimilarityMilli("", "INVOICE"));
    }

    @Test
    void blendWeightsTheTwoSimilarities() {
        assertEquals(600, Similarity.blendMilli(1000, 0, 600));
        assertEquals(400, Similarity.blendMilli(0, 1000, 600));
        assertEquals(800, Similarity.blendMilli(1000, 500, 600));
        assertEquals(399, Similarity.blendMilli(666, 0, 600));
        assertEquals(1000, Similarity.blendMilli(0, 1000, 0));
    }

    @Test
    void similaritiesAreSymmetricAndBoundedOnRandomText() {
        SplittableRandom random = new SplittableRandom(20261021L);
        String alphabet = "AB C-";
        for (int trial = 0; trial < 1000; trial++) {
            String left = randomText(random, alphabet);
            String right = randomText(random, alphabet);
            int editLeftRight = Similarity.editSimilarityMilli(left, right);
            int tokenLeftRight = Similarity.tokenSetMilli(left, right);
            assertEquals(editLeftRight, Similarity.editSimilarityMilli(right, left), left + " | " + right);
            assertEquals(tokenLeftRight, Similarity.tokenSetMilli(right, left), left + " | " + right);
            assertTrue(editLeftRight >= 0 && editLeftRight <= MatchScore.SCALE);
            assertTrue(tokenLeftRight >= 0 && tokenLeftRight <= MatchScore.SCALE);
            if (left.replace(" ", "").replace("-", "").length() > 0) {
                assertEquals(1000, Similarity.editSimilarityMilli(left, left), left);
            }
        }
    }

    private static String randomText(SplittableRandom random, String alphabet) {
        int length = random.nextInt(12);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < length; i++) {
            text.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return text.toString();
    }
}