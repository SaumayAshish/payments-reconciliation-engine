package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalOutputTest {

    private static final MatchingConfig CONFIG = MatchingConfig.defaults();
    private static final LocalDate FRIDAY = Lines.FRIDAY;

    private static String key(MatchableLine line) {
        return line.key().value();
    }

    @Test
    void theCanonicalTextHasAFixedLayoutOneRecordPerLine() {
        MatchableLine matchedStatement = Lines.statement(0, "R1", 1000, "GBP", FRIDAY);
        MatchableLine matchedLedger = Lines.ledger(0, "R1", 1000, "GBP", FRIDAY);
        MatchableLine lonelyStatement = Lines.statement(1, "R2", 2000, "GBP", FRIDAY);
        MatchableLine carried = Lines.ledger(1, "R3", 3000, "GBP", FRIDAY);

        RunResult result = ReconciliationRun.compute(List.of(lonelyStatement, matchedStatement),
                List.of(carried, matchedLedger), FRIDAY, CONFIG);

        String expected = "CANONICAL|1\n"
                + "MATCH|EXACT|1000|AUTO_CONFIRM|" + key(matchedStatement) + "|" + key(matchedLedger)
                + "|EXACT: reference, amount, currency, value date equal\n"
                + "EXCEPTION|MISSING_LEDGER|S:" + key(lonelyStatement)
                + "|MISSING_LEDGER: no ledger entry above the review threshold||\n"
                + "CARRIED|" + key(carried) + "\n";
        assertEquals(expected, result.canonicalText());
        assertEquals(64, result.outputHash().hex().length());
        assertEquals(CanonicalOutput.hash(expected), result.outputHash());
    }

    @Test
    void truncationNotesAreRecordedSortedAfterEverythingElse() {
        MatchableLine settlement = Lines.statement(0, "S1", 1000, "GBP", FRIDAY);
        List<MatchableLine> ledger = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ledger.add(Lines.ledger(i, "F" + i, 100 + i, "GBP", FRIDAY));
        }

        String text = ReconciliationRun.compute(List.of(settlement), ledger, FRIDAY, CONFIG).canonicalText();

        String lastLine = text.substring(text.lastIndexOf("NOTE|"));
        assertTrue(lastLine.startsWith("NOTE|" + OneToManyTier.TRUNCATED_NOTE));
        assertTrue(text.endsWith("\n"));
    }

    @Test
    void theTextDoesNotDependOnTheOrderTheEngineListedItsResults() {
        List<MatchableLine> statements = new ArrayList<>();
        List<MatchableLine> ledger = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            statements.add(Lines.statement(i, "R" + i, 1000 + 10 * i, "GBP", FRIDAY));
            ledger.add(Lines.ledger(i, "R" + i, 1000 + 10 * i, "GBP", FRIDAY.plusDays(i % 2 == 0 ? 0 : 5)));
        }
        PipelineResult pipeline = MatchingPipeline.run(statements, ledger, CONFIG);
        ExceptionClassifier.Classification classification = ExceptionClassifier.classify(pipeline, FRIDAY, CONFIG);

        List<Match> reversedMatches = new ArrayList<>(pipeline.matches());
        Collections.reverse(reversedMatches);
        List<RunException> reversedExceptions = new ArrayList<>(classification.exceptions());
        Collections.reverse(reversedExceptions);
        PipelineResult reversedPipeline = new PipelineResult(reversedMatches, pipeline.ambiguous(),
                pipeline.unmatchedStatements(), pipeline.unmatchedLedger(), pipeline.notes());

        assertEquals(CanonicalOutput.text(pipeline, classification), CanonicalOutput.text(reversedPipeline,
                new ExceptionClassifier.Classification(reversedExceptions, classification.carriedForward())));
        assertTrue(pipeline.matches().size() > 1 && classification.exceptions().size() > 1);
    }

    @Test
    void anyChangeInADecisionChangesTheHash() {
        List<MatchableLine> statements = List.of(Lines.statement(0, "R1", 1000, "GBP", FRIDAY));
        List<MatchableLine> ledger = List.of(Lines.ledger(0, "R1", 1000, "GBP", FRIDAY.plusDays(5)));

        RunResult review = ReconciliationRun.compute(statements, ledger, FRIDAY, CONFIG);
        RunResult auto = ReconciliationRun.compute(statements, ledger, FRIDAY, CONFIG.withThresholds(900, 700));

        assertNotEquals(review.outputHash(), auto.outputHash());
        assertEquals(1, review.summary().reviewMatches());
        assertEquals(1, auto.summary().autoConfirmedMatches());
    }
}