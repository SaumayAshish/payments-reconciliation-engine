package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.model.LineSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** NFR-04 at tier level: the outcome must not depend on the order the inputs arrive in. */
class MatchingDeterminismTest {

    private static final MatchingConfig CONFIG = MatchingConfig.defaults();
    private static final String[] REFERENCES = {"R1", "R2", "R3", "R4"};
    private static final long[] AMOUNTS = {10000, 20000};
    private static final int[] DAY_OFFSETS = {0, 1, 2, 3, 4, 7};
    private static final String[] COUNTERPARTIES = {"ACME TRADING", "ACME TRADERS", "BETA CORP"};
    private static final String[] NARRATIVES = {"INVOICE PAYMENT", "INVOICE PAYMNT", "SALARY"};

    @Test
    void shuffledInputsGiveIdenticalOutcomesAndTheTrialsAreNotTrivial() {
        SplittableRandom random = new SplittableRandom(20261021L);
        int[] matches = new int[3];
        int[] ambiguous = new int[3];
        for (int trial = 0; trial < 300; trial++) {
            List<MatchableLine> statements = world(random, true);
            List<MatchableLine> ledger = world(random, false);
            List<TierOutcome> expected = pipeline(statements, ledger);

            for (int shuffle = 0; shuffle < 3; shuffle++) {
                assertEquals(expected, pipeline(shuffled(statements, random), shuffled(ledger, random)),
                        "trial " + trial + " shuffle " + shuffle);
            }
            for (int tier = 0; tier < expected.size(); tier++) {
                matches[tier] += expected.get(tier).matches().size();
                ambiguous[tier] += expected.get(tier).ambiguous().size();
            }
        }
        for (int tier = 0; tier < matches.length; tier++) {
            assertTrue(matches[tier] > 0, "tier " + (tier + 1) + " must produce matches in the generated worlds");
            assertTrue(ambiguous[tier] > 0, "tier " + (tier + 1) + " must produce ties in the generated worlds");
        }
    }

    private static List<TierOutcome> pipeline(List<MatchableLine> statements, List<MatchableLine> ledger) {
        TierOutcome exact = PairResolver.resolve(statements, ledger, ExactTier.candidates(statements, ledger),
                CONFIG);
        TierOutcome tolerance = PairResolver.resolve(exact.remainingStatements(), exact.remainingLedger(),
                ToleranceTier.candidates(exact.remainingStatements(), exact.remainingLedger(), CONFIG), CONFIG);
        TierOutcome fuzzy = PairResolver.resolve(tolerance.remainingStatements(), tolerance.remainingLedger(),
                FuzzyTier.candidates(tolerance.remainingStatements(), tolerance.remainingLedger(), CONFIG),
                CONFIG);
        return List.of(exact, tolerance, fuzzy);
    }

    private static List<MatchableLine> world(SplittableRandom random, boolean statementSide) {
        List<MatchableLine> lines = new ArrayList<>();
        for (int seq = 0; seq < 7; seq++) {
            String reference = REFERENCES[random.nextInt(REFERENCES.length)];
            long amount = AMOUNTS[random.nextInt(AMOUNTS.length)];
            LocalDate date = Lines.FRIDAY.plusDays(DAY_OFFSETS[random.nextInt(DAY_OFFSETS.length)]);
            String counterparty = COUNTERPARTIES[random.nextInt(COUNTERPARTIES.length)];
            String narrative = NARRATIVES[random.nextInt(NARRATIVES.length)];
            lines.add(Lines.build(statementSide ? LineSource.STATEMENT : LineSource.LEDGER, Lines.ACCOUNT, seq,
                    reference, amount, "GBP", date, counterparty, narrative));
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
