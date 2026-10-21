package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchTypesTest {

    private static final LocalDate FRIDAY = Lines.FRIDAY;
    private static final MatchableLine STATEMENT = Lines.statement(0, "INV1", 5000, "GBP", FRIDAY);
    private static final MatchableLine ENTRY = Lines.ledger(0, "INV1", 5000, "GBP", FRIDAY);
    private static final MatchScore PERFECT = MatchScore.ofMilli(1000);

    @Test
    void canonicalOrderIsValueDateThenSequenceThenLineKey() {
        MatchableLine early = Lines.ledger(5, "INV1", 5000, "GBP", FRIDAY);
        MatchableLine late = Lines.ledger(1, "INV1", 5000, "GBP", FRIDAY.plusDays(1));
        MatchableLine lowSeq = Lines.ledger(2, "INV1", 5000, "GBP", FRIDAY);
        List<MatchableLine> sorted = new ArrayList<>(List.of(late, early, lowSeq));
        sorted.sort(MatchableLine.CANONICAL_ORDER);
        assertEquals(List.of(lowSeq, early, late), sorted);
    }

    @Test
    void aLineNeedsANonNegativeSequence() {
        assertThrows(IllegalArgumentException.class, () -> new MatchableLine(-1, STATEMENT.line()));
    }

    @Test
    void aPairNeedsOneLineFromEachSideAScoreInsideItsTierAndAReason() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScoredPair(STATEMENT, STATEMENT, MatchTier.EXACT, PERFECT, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new ScoredPair(ENTRY, ENTRY, MatchTier.EXACT, PERFECT, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new ScoredPair(STATEMENT, ENTRY, MatchTier.TOLERANCE, PERFECT, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new ScoredPair(STATEMENT, ENTRY, MatchTier.EXACT, PERFECT, " "));
    }

    @Test
    void pairsAreOrderedBestScoreFirstThenLedgerThenStatement() {
        MatchableLine nextEntry = Lines.ledger(1, "INV1", 5000, "GBP", FRIDAY);
        ScoredPair best = new ScoredPair(STATEMENT, ENTRY, MatchTier.EXACT, PERFECT, "a");
        ScoredPair sameScoreLaterEntry = new ScoredPair(STATEMENT, nextEntry, MatchTier.EXACT, PERFECT, "b");
        ScoredPair lower = new ScoredPair(STATEMENT, ENTRY, MatchTier.TOLERANCE, MatchScore.ofMilli(930), "c");
        List<ScoredPair> sorted = new ArrayList<>(List.of(lower, sameScoreLaterEntry, best));
        sorted.sort(ScoredPair.ORDER);
        assertEquals(List.of(best, sameScoreLaterEntry, lower), sorted);
    }

    @Test
    void aMatchNeedsLinesOnBothSidesAScoreInsideItsTierAndAReason() {
        List<MatchableLine> none = List.of();
        List<MatchableLine> one = List.of(ENTRY);
        List<MatchableLine> statements = List.of(STATEMENT);
        assertThrows(IllegalArgumentException.class,
                () -> new Match(MatchTier.EXACT, PERFECT, MatchBand.AUTO_CONFIRM, "r", none, one));
        assertThrows(IllegalArgumentException.class,
                () -> new Match(MatchTier.EXACT, PERFECT, MatchBand.AUTO_CONFIRM, "r", statements, none));
        assertThrows(IllegalArgumentException.class,
                () -> new Match(MatchTier.FUZZY, PERFECT, MatchBand.AUTO_CONFIRM, "r", statements, one));
        assertThrows(IllegalArgumentException.class,
                () -> new Match(MatchTier.EXACT, PERFECT, MatchBand.AUTO_CONFIRM, "", statements, one));
    }

    @Test
    void matchesAreImmutableCopies() {
        List<MatchableLine> statements = new ArrayList<>(List.of(STATEMENT));
        Match match = new Match(MatchTier.EXACT, PERFECT, MatchBand.AUTO_CONFIRM, "r", statements,
                List.of(ENTRY));
        statements.clear();
        assertEquals(1, match.statementSide().size());
        assertThrows(UnsupportedOperationException.class, () -> match.statementSide().clear());
    }

    @Test
    void anAmbiguousGroupNeedsLinesOnBothSides() {
        assertThrows(IllegalArgumentException.class,
                () -> new AmbiguousGroup(MatchTier.EXACT, PERFECT, List.of(), List.of(ENTRY)));
        assertThrows(IllegalArgumentException.class,
                () -> new AmbiguousGroup(MatchTier.EXACT, PERFECT, List.of(STATEMENT), List.of()));
        assertTrue(AmbiguousGroup.REASON.equals("AMBIGUOUS_TIE"));
    }
}