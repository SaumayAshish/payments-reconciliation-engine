package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.model.LineSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class OneToManyTierTest {

    private static final MatchingConfig CONFIG = MatchingConfig.defaults();
    private static final LocalDate FRIDAY = Lines.FRIDAY;
    private static final LocalDate MONDAY = FRIDAY.plusDays(3);

    private static MatchableLine statement(long seq, String reference, long minor, LocalDate date) {
        return Lines.statement(seq, reference, minor, "GBP", date);
    }

    private static MatchableLine entry(long seq, String reference, long minor, LocalDate date) {
        return Lines.ledger(seq, reference, minor, "GBP", date);
    }

    private static TierOutcome run(List<MatchableLine> statements, List<MatchableLine> ledger) {
        return OneToManyTier.run(statements, ledger, CONFIG);
    }

    @Test
    void oneStatementLineEqualToTheSumOfThreeLedgerEntriesIsProposedForReview() {
        MatchableLine settlement = statement(0, "SETTLE1", 100000, FRIDAY);
        MatchableLine first = entry(0, "INV1", 60000, FRIDAY);
        MatchableLine second = entry(1, "INV2", 30000, FRIDAY);
        MatchableLine third = entry(2, "INV3", 10000, FRIDAY);

        TierOutcome outcome = run(List.of(settlement), List.of(third, first, second));

        assertEquals(1, outcome.matches().size());
        Match match = outcome.matches().get(0);
        assertEquals(MatchTier.ONE_TO_MANY, match.tier());
        assertEquals(830, match.score().milli());
        assertEquals(MatchBand.REVIEW, match.band());
        assertEquals(List.of(settlement), match.statementSide());
        assertEquals(List.of(first, second, third), match.ledgerSide());
        assertEquals("ONE_TO_MANY: statement line equals the sum of 3 ledger entries; value dates equal",
                match.reason());
        assertEquals(List.of(), outcome.remainingStatements());
        assertEquals(List.of(), outcome.remainingLedger());
        assertEquals(List.of(), outcome.notes());
    }

    @Test
    void theReverseDirectionManyStatementLinesForOneLedgerEntryIsProposedToo() {
        MatchableLine settlement = entry(0, "SETTLE1", 100000, FRIDAY);
        MatchableLine first = statement(0, "INV1", 60000, FRIDAY);
        MatchableLine second = statement(1, "INV2", 40000, FRIDAY);

        TierOutcome outcome = run(List.of(second, first), List.of(settlement));

        assertEquals(1, outcome.matches().size());
        Match match = outcome.matches().get(0);
        assertEquals(List.of(first, second), match.statementSide());
        assertEquals(List.of(settlement), match.ledgerSide());
        assertEquals("ONE_TO_MANY: ledger entry equals the sum of 2 statement lines; value dates equal",
                match.reason());
    }

    @Test
    void aSharedBatchReferenceAndIdenticalDatesRaiseTheScore() {
        TierOutcome both = run(List.of(statement(0, "BATCH7", 1000, FRIDAY)),
                List.of(entry(0, "BATCH7", 600, FRIDAY), entry(1, "BATCH7", 400, FRIDAY)));
        TierOutcome referenceOnly = run(List.of(statement(0, "BATCH7", 1000, FRIDAY)),
                List.of(entry(0, "BATCH7", 600, FRIDAY), entry(1, "BATCH7", 400, MONDAY)));
        TierOutcome neither = run(List.of(statement(0, "BATCH7", 1000, FRIDAY)),
                List.of(entry(0, "INV1", 600, FRIDAY), entry(1, "INV2", 400, MONDAY)));

        assertEquals(900, both.matches().get(0).score().milli());
        assertEquals(870, referenceOnly.matches().get(0).score().milli());
        assertTrue(referenceOnly.matches().get(0).reason().endsWith("; batch reference shared"));
        assertEquals(800, neither.matches().get(0).score().milli());
        assertEquals("ONE_TO_MANY: statement line equals the sum of 2 ledger entries",
                neither.matches().get(0).reason());
    }

    @Test
    void aSumThatIsNotExactIsNotAMatch() {
        TierOutcome outcome = run(List.of(statement(0, "S1", 1000, FRIDAY)),
                List.of(entry(0, "L1", 600, FRIDAY), entry(1, "L2", 399, FRIDAY)));

        assertEquals(List.of(), outcome.matches());
        assertEquals(1, outcome.remainingStatements().size());
        assertEquals(2, outcome.remainingLedger().size());
    }

    @Test
    void aSingleEntryEqualToTheTargetIsNotADecomposition() {
        TierOutcome outcome = run(List.of(statement(0, "S1", 1000, FRIDAY)), List.of(entry(0, "L1", 1000, FRIDAY)));
        assertEquals(List.of(), outcome.matches());
    }

    @Test
    void componentsMustShareTheSignOfTheSingleLine() {
        // 600 + 400 in magnitude, but -400 is money going the other way: the real sum is 200.
        assertEquals(List.of(), run(List.of(statement(0, "S1", 1000, FRIDAY)),
                List.of(entry(0, "L1", 600, FRIDAY), entry(1, "L2", -400, FRIDAY))).matches());

        TierOutcome negative = run(List.of(statement(0, "S1", -1000, FRIDAY)),
                List.of(entry(0, "L1", -600, FRIDAY), entry(1, "L2", -400, FRIDAY)));
        assertEquals(1, negative.matches().size());
    }

    @Test
    void componentsMustBeInsideTheDateWindow() {
        MatchableLine settlement = statement(0, "S1", 1000, FRIDAY);

        assertEquals(List.of(), run(List.of(settlement),
                List.of(entry(0, "L1", 600, FRIDAY), entry(1, "L2", 400, FRIDAY.plusDays(10)))).matches());
        assertEquals(1, run(List.of(settlement),
                List.of(entry(0, "L1", 600, FRIDAY), entry(1, "L2", 400, MONDAY))).matches().size());
    }

    @Test
    void componentsMustShareAccountAndCurrency() {
        MatchableLine settlement = statement(0, "S1", 1000, FRIDAY);
        MatchableLine gbp = entry(0, "L1", 600, FRIDAY);

        MatchableLine usd = Lines.ledger(1, "L2", 400, "USD", FRIDAY);
        MatchableLine otherAccount = Lines.build(LineSource.LEDGER, "ACC-2", 2, "L3", 400, "GBP", FRIDAY,
                "ACME LTD", "INVOICE PAYMENT");

        assertEquals(List.of(), run(List.of(settlement), List.of(gbp, usd)).matches());
        assertEquals(List.of(), run(List.of(settlement), List.of(gbp, otherAccount)).matches());
    }

    @Test
    void twoDifferentWaysToMakeTheSameTotalAreNeverAutoConfirmed() {
        MatchableLine settlement = statement(0, "S1", 1000, FRIDAY);
        List<MatchableLine> ledger = List.of(entry(0, "L1", 600, FRIDAY), entry(1, "L2", 400, FRIDAY),
                entry(2, "L3", 500, FRIDAY), entry(3, "L4", 500, FRIDAY));

        TierOutcome outcome = run(List.of(settlement), ledger);

        assertEquals(List.of(), outcome.matches());
        assertEquals(1, outcome.ambiguous().size());
        AmbiguousGroup group = outcome.ambiguous().get(0);
        assertEquals(List.of(settlement), group.statementSide());
        assertEquals(ledger, group.ledgerSide());
        assertEquals(MatchTier.ONE_TO_MANY, group.tier());
        assertEquals(List.of(), outcome.remainingLedger());
    }

    @Test
    void twoStatementLinesThatCouldClaimTheSameEntriesAreAmbiguousTogether() {
        MatchableLine first = statement(0, "S1", 1000, FRIDAY);
        MatchableLine second = statement(1, "S2", 1000, FRIDAY);
        List<MatchableLine> ledger = List.of(entry(0, "L1", 600, FRIDAY), entry(1, "L2", 400, FRIDAY));

        TierOutcome outcome = run(List.of(second, first), ledger);

        assertEquals(List.of(), outcome.matches());
        assertEquals(List.of(first, second), outcome.ambiguous().get(0).statementSide());
        assertEquals(ledger, outcome.ambiguous().get(0).ledgerSide());
    }

    @Test
    void independentDecompositionsAreBothAccepted() {
        MatchableLine big = statement(0, "S1", 1000, FRIDAY);
        MatchableLine small = statement(1, "S2", 500, FRIDAY);
        MatchableLine l600 = entry(0, "L1", 600, FRIDAY);
        MatchableLine l400 = entry(1, "L2", 400, FRIDAY);
        MatchableLine l300 = entry(2, "L3", 300, FRIDAY);
        MatchableLine l200 = entry(3, "L4", 200, FRIDAY);

        TierOutcome outcome = run(List.of(small, big), List.of(l200, l300, l400, l600));

        assertEquals(2, outcome.matches().size());
        assertEquals(List.of(big), outcome.matches().get(0).statementSide());
        assertEquals(List.of(l600, l400), outcome.matches().get(0).ledgerSide());
        assertEquals(List.of(small), outcome.matches().get(1).statementSide());
        assertEquals(List.of(l300, l200), outcome.matches().get(1).ledgerSide());
        assertEquals(List.of(), outcome.ambiguous());
    }

    @Test
    void linesNotInAnyDecompositionStayAvailable() {
        MatchableLine settlement = statement(0, "S1", 1000, FRIDAY);
        MatchableLine unrelated = statement(1, "S2", 77, FRIDAY);
        MatchableLine l600 = entry(0, "L1", 600, FRIDAY);
        MatchableLine l400 = entry(1, "L2", 400, FRIDAY);
        MatchableLine stray = entry(2, "L3", 5555, FRIDAY);

        TierOutcome outcome = run(List.of(unrelated, settlement), List.of(stray, l400, l600));

        assertEquals(1, outcome.matches().size());
        assertEquals(List.of(unrelated), outcome.remainingStatements());
        assertEquals(List.of(stray), outcome.remainingLedger());
    }

    @Test
    void onlyTheBestCandidatesAreSearchedAndATruncationIsRecorded() {
        MatchableLine settlement = statement(0, "S1", 1000, FRIDAY);
        List<MatchableLine> ledger = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ledger.add(entry(i, "F" + i, 999, FRIDAY));
        }
        ledger.add(entry(8, "L1", 600, MONDAY));
        ledger.add(entry(9, "L2", 400, MONDAY));

        TierOutcome narrow = run(List.of(settlement), ledger);

        assertEquals(List.of(), narrow.matches());
        assertEquals(1, narrow.notes().size());
        assertTrue(narrow.notes().get(0).startsWith(OneToManyTier.TRUNCATED_NOTE));
        assertTrue(narrow.notes().get(0).contains("had 10 eligible candidates, considered 8"));

        MatchingConfig wide = new MatchingConfig(950, 700, 3, 10, 12, 600, 600);
        TierOutcome wideOutcome = OneToManyTier.run(List.of(settlement), ledger, wide);

        assertEquals(1, wideOutcome.matches().size());
        assertEquals(List.of(), wideOutcome.notes());
    }

    @Test
    void largeAmountsNeverOverflowTheSearch() {
        long nineQuintillion = 9_000_000_000_000_000_000L;
        MatchableLine settlement = statement(0, "S1", nineQuintillion, FRIDAY);
        MatchableLine six = entry(0, "L1", 6_000_000_000_000_000_000L, FRIDAY);
        MatchableLine five = entry(1, "L2", 5_000_000_000_000_000_000L, FRIDAY);
        MatchableLine three = entry(2, "L3", 3_000_000_000_000_000_000L, FRIDAY);

        TierOutcome outcome = run(List.of(settlement), List.of(five, six, three));

        assertEquals(1, outcome.matches().size());
        assertEquals(List.of(six, three), outcome.matches().get(0).ledgerSide());
    }

    @Test
    void searchAgreesWithABruteForceOracleOnRandomSmallWorlds() {
        SplittableRandom random = new SplittableRandom(20261021L);
        long[] amounts = {100, 200, 300, 400, 500, 600, 700, 1000, 1500};
        int[] offsets = {0, 1, 2, 3, 4, 7, 8};
        int decompositions = 0;
        for (int trial = 0; trial < 300; trial++) {
            List<MatchableLine> statements = randomLines(random, true, 4, amounts, offsets);
            List<MatchableLine> ledger = randomLines(random, false, 6, amounts, offsets);
            List<String> notes = new ArrayList<>();

            Set<String> actual = new TreeSet<>();
            for (Proposal proposal : OneToManyTier.proposals(statements, ledger, CONFIG, notes)) {
                actual.add(signature(proposal.single(), proposal.singleIsStatement(), proposal.components()));
            }
            Set<String> expected = new TreeSet<>();
            expected.addAll(oracle(statements, ledger, true));
            expected.addAll(oracle(ledger, statements, false));

            assertEquals(expected, actual, "trial " + trial);
            assertEquals(List.of(), notes);
            decompositions += expected.size();
        }
        assertTrue(decompositions > 0, "the generated worlds must contain decompositions");
    }

    private static List<MatchableLine> randomLines(SplittableRandom random, boolean statementSide, int count,
                                                   long[] amounts, int[] offsets) {
        List<MatchableLine> lines = new ArrayList<>();
        for (int seq = 0; seq < count; seq++) {
            long amount = amounts[random.nextInt(amounts.length)];
            LocalDate date = FRIDAY.plusDays(offsets[random.nextInt(offsets.length)]);
            lines.add(statementSide ? statement(seq, "R" + seq, amount, date) : entry(seq, "R" + seq, amount, date));
        }
        return lines;
    }

    /** Every subset of two or more eligible lines whose magnitudes add up to the single line's, found by bitmask. */
    private static Set<String> oracle(List<MatchableLine> singles, List<MatchableLine> pool,
                                      boolean singleIsStatement) {
        Set<String> found = new TreeSet<>();
        for (MatchableLine single : singles) {
            long target = single.line().amount().minorUnits();
            List<MatchableLine> eligible = new ArrayList<>();
            for (MatchableLine line : pool) {
                boolean inWindow = com.saumay.payments.domain.model.BusinessCalendar.businessDaysBetween(
                        single.line().valueDate(), line.line().valueDate()) <= CONFIG.dateWindowBusinessDays();
                if (line.line().amount().minorUnits() < target && inWindow) {
                    eligible.add(line);
                }
            }
            for (int mask = 1; mask < (1 << eligible.size()); mask++) {
                List<MatchableLine> subset = new ArrayList<>();
                long sum = 0;
                for (int i = 0; i < eligible.size(); i++) {
                    if ((mask & (1 << i)) != 0) {
                        subset.add(eligible.get(i));
                        sum += eligible.get(i).line().amount().minorUnits();
                    }
                }
                if (subset.size() >= 2 && sum == target) {
                    found.add(signature(single, singleIsStatement, subset));
                }
            }
        }
        return found;
    }

    private static String signature(MatchableLine single, boolean singleIsStatement,
                                    List<MatchableLine> components) {
        List<String> keys = new ArrayList<>();
        for (MatchableLine component : components) {
            keys.add(component.key().value());
        }
        keys.sort(null);
        return (singleIsStatement ? "S:" : "L:") + single.key().value() + "=" + String.join(",", keys);
    }
}