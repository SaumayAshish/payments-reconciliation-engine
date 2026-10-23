package com.saumay.payments.matching.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.matching.MatchableLine;
import com.saumay.payments.matching.MatchingConfig;
import com.saumay.payments.matching.MatchingPipeline;
import com.saumay.payments.matching.PipelineResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** NFR-02 and NFR-04 on the labelled corpus: the engine is measured against ground truth, never trusted. */
class CorpusEvaluationTest {

    private static final long SEED = 20261022L;
    private static final int LINES = 3000;
    private static final MatchingConfig CONFIG = MatchingConfig.defaults();

    @Test
    void noFalseAutoMatchesAndEveryTruthCaseIsHandledAsLabelled() throws IOException {
        Corpus corpus = CorpusGenerator.generate(SEED, LINES, CorpusProfile.standard());
        PipelineResult result = MatchingPipeline.run(corpus.statements(), corpus.ledger(), CONFIG);

        Evaluation evaluation = CorpusEvaluator.evaluate(corpus, result);

        Path report = Path.of("target", "corpus-report.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, evaluation.summary(), StandardCharsets.UTF_8);
        System.out.println(evaluation.summary());

        assertEquals(List.of(), evaluation.falseAutoMatches(), "NFR-02: zero false auto-matches");
        assertEquals(List.of(), evaluation.failures());
        assertEquals(evaluation.casesTotal(), evaluation.casesPassed());
        assertEquals(0, evaluation.incorrectReviewMatches(), "no review proposal may disagree with the truth");
    }

    @Test
    void theAutoMatchRateOnTheStandardProfileIsReportedAndAboveTheBrdTarget() {
        Corpus corpus = CorpusGenerator.generate(SEED, LINES, CorpusProfile.standard());
        Evaluation evaluation = CorpusEvaluator.evaluate(corpus,
                MatchingPipeline.run(corpus.statements(), corpus.ledger(), CONFIG));

        assertTrue(evaluation.autoMatchRatePermille() >= 900,
                "auto match rate was " + evaluation.autoMatchRatePermille() + " per mille");
    }

    @Test
    void theGateHoldsAcrossSeveralSeeds() {
        for (long seed = 1; seed <= 6; seed++) {
            Corpus corpus = CorpusGenerator.generate(seed, 2000, CorpusProfile.standard());
            Evaluation evaluation = CorpusEvaluator.evaluate(corpus,
                    MatchingPipeline.run(corpus.statements(), corpus.ledger(), CONFIG));
            assertEquals(List.of(), evaluation.falseAutoMatches(), "seed " + seed);
            assertEquals(List.of(), evaluation.failures(), "seed " + seed);
        }
    }

    @Test
    void aDenseCorpusMayLoseTierFourRecallButNeverSafety() {
        // Batches share accounts and dates with the leftovers of everything else, packed into 10 business days:
        // many leftovers inside one window swamp the 8-candidate search.
        Corpus corpus = CorpusGenerator.generate(SEED, LINES, CorpusProfile.standard(),
                CorpusGenerator.Layout.CROWDED, 10);
        Evaluation evaluation = CorpusEvaluator.evaluate(corpus,
                MatchingPipeline.run(corpus.statements(), corpus.ledger(), CONFIG));

        assertEquals(List.of(), evaluation.falseAutoMatches(), "truncation must never create an auto-match");
        assertTrue(evaluation.truncationNotes() > 0, "the dense corpus must actually truncate");
        for (Scenario scenario : Scenario.values()) {
            int[] counts = evaluation.casesByScenario().get(scenario);
            if (scenario != Scenario.ONE_TO_MANY && scenario != Scenario.SUBSET_AMBIGUITY) {
                assertEquals(counts[0], counts[1], scenario + " does not depend on tier 4 candidate density");
            }
        }
    }

    @Test
    void shuffledInputsGiveTheIdenticalResultOnTheWholeCorpus() {
        Corpus corpus = CorpusGenerator.generate(SEED, LINES, CorpusProfile.standard());
        PipelineResult expected = MatchingPipeline.run(corpus.statements(), corpus.ledger(), CONFIG);

        SplittableRandom random = new SplittableRandom(7);
        for (int shuffle = 0; shuffle < 3; shuffle++) {
            PipelineResult actual = MatchingPipeline.run(shuffled(corpus.statements(), random),
                    shuffled(corpus.ledger(), random), CONFIG);
            assertEquals(expected, actual, "shuffle " + shuffle);
        }
    }

    private static List<MatchableLine> shuffled(List<MatchableLine> lines, SplittableRandom random) {
        List<MatchableLine> copy = new ArrayList<>(lines);
        for (int i = copy.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            MatchableLine swap = copy.get(i);
            copy.set(i, copy.get(j));
            copy.set(j, swap);
        }
        return copy;
    }
}