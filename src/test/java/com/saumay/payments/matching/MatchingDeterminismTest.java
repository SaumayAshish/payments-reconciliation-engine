package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** NFR-04 at tier level: the outcome must not depend on the order the inputs arrive in. */
class MatchingDeterminismTest {

    private static final MatchingConfig CONFIG = MatchingConfig.defaults();
    private static final String[] REFERENCES = {"R1", "R2", "R3"};
    private static final long[] AMOUNTS = {10000, 20000};
    private static final int[] DAY_OFFSETS = {0, 1, 2, 3, 4, 7};

    @Test
    void shuffledInputsGiveIdenticalOutcomesAndTheTrialsAreNotTrivial() {
        SplittableRandom random = new SplittableRandom(20261021L);
        int totalMatches = 0;
        int totalAmbiguous = 0;
        for (int trial = 0; trial < 300; trial++) {
            List<MatchableLine> statements = world(random, true);
            List<MatchableLine> ledger = world(random, false);
            List<TierOutcome> expected = pipeline(statements, ledger);

            for (int shuffle = 0; shuffle < 3; shuffle++) {
                assertEquals(expected, pipeline(shuffled(statements, random), shuffled(ledger, random)),
                        "trial " + trial + " shuffle " + shuffle);
            }
            for (TierOutcome outcome : expected) {
                totalMatches += outcome.matches().size();
                totalAmbiguous += outcome.ambiguous().size();
            }
        }
        assertTrue(totalMatches > 0, "the generated worlds must produce matches");
        assertTrue(totalAmbiguous > 0, "the generated worlds must produce ambiguous ties");
    }

    private static List<TierOutcome> pipeline(List<MatchableLine> statements, List<MatchableLine> ledger) {
        TierOutcome exact = PairResolver.resolve(statements, ledger, ExactTier.candidates(statements, ledger),
                CONFIG);
        TierOutcome tolerance = PairResolver.resolve(exact.remainingStatements(), exact.remainingLedger(),
                ToleranceTier.candidates(exact.remainingStatements(), exact.remainingLedger(), CONFIG), CONFIG);
        return List.of(exact, tolerance);
    }

    private static List<MatchableLine> world(SplittableRandom random, boolean statementSide) {
        List<MatchableLine> lines = new ArrayList<>();
        for (int seq = 0; seq < 7; seq++) {
            String reference = REFERENCES[random.nextInt(REFERENCES.length)];
            long amount = AMOUNTS[random.nextInt(AMOUNTS.length)];
            LocalDate date = Lines.FRIDAY.plusDays(DAY_OFFSETS[random.nextInt(DAY_OFFSETS.length)]);
            lines.add(statementSide ? Lines.statement(seq, reference, amount, "GBP", date)
                    : Lines.ledger(seq, reference, amount, "GBP", date));
        }
        return lines;
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