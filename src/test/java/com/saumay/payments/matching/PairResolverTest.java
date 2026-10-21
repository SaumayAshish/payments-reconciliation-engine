package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PairResolverTest {

    private static final MatchingConfig CONFIG = MatchingConfig.defaults();
    private static final LocalDate FRIDAY = Lines.FRIDAY;
    private static final LocalDate MONDAY = FRIDAY.plusDays(3);

    private static TierOutcome exact(List<MatchableLine> statements, List<MatchableLine> ledger) {
        return PairResolver.resolve(statements, ledger, ExactTier.candidates(statements, ledger), CONFIG);
    }

    private static TierOutcome tolerance(List<MatchableLine> statements, List<MatchableLine> ledger) {
        return PairResolver.resolve(statements, ledger, ToleranceTier.candidates(statements, ledger, CONFIG),
                CONFIG);
    }

    @Test
    void anUnambiguousExactPairIsAutoConfirmedAndLeavesThePool() {
        MatchableLine statement = Lines.statement(0, "INV1", 5000, "GBP", FRIDAY);
        MatchableLine entry = Lines.ledger(0, "INV1", 5000, "GBP", FRIDAY);

        TierOutcome outcome = exact(List.of(statement), List.of(entry));

        assertEquals(1, outcome.matches().size());
        Match match = outcome.matches().get(0);
        assertEquals(MatchBand.AUTO_CONFIRM, match.band());
        assertEquals(List.of(statement), match.statementSide());
        assertEquals(List.of(entry), match.ledgerSide());
        assertEquals(List.of(), outcome.ambiguous());
        assertEquals(List.of(), outcome.remainingStatements());
        assertEquals(List.of(), outcome.remainingLedger());
    }

    @Test
    void aToleranceMatchTwoDaysApartIsProposedForReviewButStillClaimsBothLines() {
        MatchableLine statement = Lines.statement(0, "INV1", 5000, "GBP", MONDAY.plusDays(1));
        MatchableLine entry = Lines.ledger(0, "INV1", 5000, "GBP", FRIDAY);

        TierOutcome outcome = tolerance(List.of(statement), List.of(entry));

        assertEquals(MatchBand.REVIEW, outcome.matches().get(0).band());
        assertEquals(930, outcome.matches().get(0).score().milli());
        assertEquals(List.of(), outcome.remainingStatements());
        assertEquals(List.of(), outcome.remainingLedger());
    }

    @Test
    void aToleranceMatchOneDayApartAutoConfirmsUnderTheDefaultThreshold() {
        MatchableLine statement = Lines.statement(0, "INV1", 5000, "GBP", MONDAY);
        MatchableLine entry = Lines.ledger(0, "INV1", 5000, "GBP", FRIDAY);

        assertEquals(MatchBand.AUTO_CONFIRM, tolerance(List.of(statement), List.of(entry)).matches().get(0).band());
    }

    @Test
    void unrelatedLinesStayInThePoolInCanonicalOrder() {
        MatchableLine matched = Lines.statement(0, "INV1", 5000, "GBP", FRIDAY);
        MatchableLine lateStatement = Lines.statement(2, "INV9", 700, "GBP", MONDAY);
        MatchableLine earlyStatement = Lines.statement(1, "INV8", 800, "GBP", FRIDAY);
        MatchableLine entry = Lines.ledger(0, "INV1", 5000, "GBP", FRIDAY);
        MatchableLine otherEntry = Lines.ledger(1, "INV7", 900, "GBP", FRIDAY);

        TierOutcome outcome = exact(List.of(lateStatement, matched, earlyStatement), List.of(otherEntry, entry));

        assertEquals(1, outcome.matches().size());
        assertEquals(List.of(earlyStatement, lateStatement), outcome.remainingStatements());
        assertEquals(List.of(otherEntry), outcome.remainingLedger());
    }

    @Test
    void twoStatementLinesCompetingForOneEntryAreNeverAutoConfirmed() {
        MatchableLine first = Lines.statement(0, "INV1", 5000, "GBP", FRIDAY);
        MatchableLine second = Lines.statement(1, "INV1", 5000, "GBP", FRIDAY);
        MatchableLine entry = Lines.ledger(0, "INV1", 5000, "GBP", FRIDAY);

        TierOutcome outcome = exact(List.of(first, second), List.of(entry));

        assertEquals(List.of(), outcome.matches());
        assertEquals(1, outcome.ambiguous().size());
        AmbiguousGroup group = outcome.ambiguous().get(0);
        assertEquals(List.of(first, second), group.statementSide());
        assertEquals(List.of(entry), group.ledgerSide());
        assertEquals(1000, group.score().milli());
        assertEquals(List.of(), outcome.remainingStatements());
        assertEquals(List.of(), outcome.remainingLedger());
    }

    @Test
    void oneStatementLineWithTwoEqualEntriesIsNeverAutoConfirmed() {
        MatchableLine statement = Lines.statement(0, "INV1", 5000, "GBP", FRIDAY);
        MatchableLine first = Lines.ledger(0, "INV1", 5000, "GBP", FRIDAY);
        MatchableLine second = Lines.ledger(1, "INV1", 5000, "GBP", FRIDAY);

        TierOutcome outcome = exact(List.of(statement), List.of(second, first));

        assertEquals(List.of(), outcome.matches());
        assertEquals(List.of(first, second), outcome.ambiguous().get(0).ledgerSide());
    }

    @Test
    void anAmbiguityDoesNotBlockAnUnrelatedPairInTheSameTier() {
        MatchableLine a1 = Lines.statement(0, "INV1", 5000, "GBP", FRIDAY);
        MatchableLine a2 = Lines.statement(1, "INV1", 5000, "GBP", FRIDAY);
        MatchableLine b = Lines.statement(2, "INV2", 7000, "GBP", FRIDAY);
        MatchableLine la = Lines.ledger(0, "INV1", 5000, "GBP", FRIDAY);
        MatchableLine lb = Lines.ledger(1, "INV2", 7000, "GBP", FRIDAY);

        TierOutcome outcome = exact(List.of(a1, a2, b), List.of(la, lb));

        assertEquals(1, outcome.matches().size());
        assertEquals(List.of(b), outcome.matches().get(0).statementSide());
        assertEquals(1, outcome.ambiguous().size());
    }

    @Test
    void theBetterScoredPairClaimsAnEntryBeforeAWorseOneCanTakeIt() {
        // The entry is on Friday. One statement is a business day away (0.960), the other three (0.900).
        MatchableLine near = Lines.statement(0, "INV1", 5000, "GBP", MONDAY);
        MatchableLine far = Lines.statement(1, "INV1", 5000, "GBP", MONDAY.plusDays(2));
        MatchableLine entry = Lines.ledger(0, "INV1", 5000, "GBP", FRIDAY);

        TierOutcome outcome = tolerance(List.of(far, near), List.of(entry));

        assertEquals(1, outcome.matches().size());
        assertEquals(List.of(near), outcome.matches().get(0).statementSide());
        assertEquals(List.of(far), outcome.remainingStatements());
        assertEquals(List.of(), outcome.ambiguous());
    }

    @Test
    void membersOfAnAmbiguousGroupAreHeldBackFromTheirWorseCandidates() {
        // Both statements are one business day from entry A (0.960), a tie. The first statement also has a
        // worse option, entry B three business days away (0.900). It must not fall through to B: the cluster
        // waits for a human and B stays free for someone else.
        MatchableLine first = Lines.statement(0, "INV1", 5000, "GBP", FRIDAY);
        MatchableLine second = Lines.statement(1, "INV1", 5000, "GBP", FRIDAY.plusDays(4));
        MatchableLine entryA = Lines.ledger(0, "INV1", 5000, "GBP", MONDAY);
        MatchableLine entryB = Lines.ledger(1, "INV1", 5000, "GBP", FRIDAY.minusDays(3));

        TierOutcome outcome = tolerance(List.of(first, second), List.of(entryA, entryB));

        assertEquals(List.of(), outcome.matches());
        assertEquals(1, outcome.ambiguous().size());
        assertEquals(List.of(first, second), outcome.ambiguous().get(0).statementSide());
        assertEquals(List.of(entryA), outcome.ambiguous().get(0).ledgerSide());
        assertEquals(List.of(entryB), outcome.remainingLedger());
        assertEquals(List.of(), outcome.remainingStatements());
    }

    @Test
    void pairsFromDifferentTiersCannotBeResolvedTogether() {
        MatchableLine statement = Lines.statement(0, "INV1", 5000, "GBP", FRIDAY);
        MatchableLine entry = Lines.ledger(0, "INV1", 5000, "GBP", FRIDAY);
        MatchableLine laterEntry = Lines.ledger(1, "INV1", 5000, "GBP", MONDAY);
        List<ScoredPair> mixed = new ArrayList<>(ExactTier.candidates(List.of(statement), List.of(entry)));
        mixed.addAll(ToleranceTier.candidates(List.of(statement), List.of(laterEntry), CONFIG));

        assertThrows(IllegalArgumentException.class,
                () -> PairResolver.resolve(List.of(statement), List.of(entry, laterEntry), mixed, CONFIG));
    }

    @Test
    void noPairsMeansNothingChanges() {
        MatchableLine statement = Lines.statement(0, "INV1", 5000, "GBP", FRIDAY);
        MatchableLine entry = Lines.ledger(0, "INV2", 5000, "GBP", FRIDAY);

        TierOutcome outcome = exact(List.of(statement), List.of(entry));

        assertEquals(List.of(), outcome.matches());
        assertEquals(List.of(statement), outcome.remainingStatements());
        assertEquals(List.of(entry), outcome.remainingLedger());
    }
}