package com.saumay.payments.matching.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.matching.Match;
import com.saumay.payments.matching.MatchBand;
import com.saumay.payments.matching.MatchTier;
import com.saumay.payments.matching.MatchingConfig;
import com.saumay.payments.matching.MatchingPipeline;
import com.saumay.payments.matching.PipelineResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The gate is only worth something if it can fail. These tests doctor a correct engine result and check that the
 * evaluator notices, which is how the zero-false-auto-match assertion is itself checked (mutation style).
 */
class CorpusEvaluatorTest {

    private static final MatchingConfig CONFIG = MatchingConfig.defaults();

    private static Corpus corpus() {
        return CorpusGenerator.generate(20261022L, 1000, CorpusProfile.standard());
    }

    @Test
    void aCorrectResultHasNoFalseAutoMatchesAndPassesEveryCase() {
        Corpus corpus = corpus();
        Evaluation evaluation = CorpusEvaluator.evaluate(corpus,
                MatchingPipeline.run(corpus.statements(), corpus.ledger(), CONFIG));

        assertEquals(List.of(), evaluation.falseAutoMatches());
        assertEquals(evaluation.casesTotal(), evaluation.casesPassed());
        assertTrue(evaluation.autoMatches() > 0);
    }

    @Test
    void aFuzzyProposalPromotedToAutoConfirmIsCaughtAsAFalseAutoMatch() {
        Corpus corpus = corpus();
        PipelineResult real = MatchingPipeline.run(corpus.statements(), corpus.ledger(), CONFIG);
        List<Match> doctored = new ArrayList<>();
        for (Match match : real.matches()) {
            doctored.add(match.tier() == MatchTier.FUZZY ? new Match(match.tier(), match.score(),
                    MatchBand.AUTO_CONFIRM, match.reason(), match.statementSide(), match.ledgerSide()) : match);
        }

        Evaluation evaluation = CorpusEvaluator.evaluate(corpus, new PipelineResult(doctored, real.ambiguous(),
                real.unmatchedStatements(), real.unmatchedLedger(), real.notes()));

        assertFalse(evaluation.falseAutoMatches().isEmpty());
        assertFalse(evaluation.failures().isEmpty());
    }

    @Test
    void aMatchBetweenTwoLinesOfDifferentTruthCasesIsCaughtAsAFalseAutoMatch() {
        Corpus corpus = corpus();
        PipelineResult real = MatchingPipeline.run(corpus.statements(), corpus.ledger(), CONFIG);
        Match first = real.matches().get(0);
        Match second = real.matches().get(1);
        List<Match> doctored = new ArrayList<>(real.matches().subList(2, real.matches().size()));
        doctored.add(new Match(first.tier(), first.score(), first.band(), first.reason(), first.statementSide(),
                second.ledgerSide()));

        Evaluation evaluation = CorpusEvaluator.evaluate(corpus, new PipelineResult(doctored, real.ambiguous(),
                real.unmatchedStatements(), real.unmatchedLedger(), real.notes()));

        assertEquals(1, evaluation.falseAutoMatches().size());
    }

    @Test
    void aTrueMatchTheEngineDroppedIsReportedAsAFailedCase() {
        Corpus corpus = corpus();
        PipelineResult real = MatchingPipeline.run(corpus.statements(), corpus.ledger(), CONFIG);

        Evaluation evaluation = CorpusEvaluator.evaluate(corpus, new PipelineResult(
                real.matches().subList(1, real.matches().size()), real.ambiguous(), real.unmatchedStatements(),
                real.unmatchedLedger(), real.notes()));

        assertEquals(evaluation.casesTotal() - 1, evaluation.casesPassed());
        assertEquals(1, evaluation.failures().size());
    }
}