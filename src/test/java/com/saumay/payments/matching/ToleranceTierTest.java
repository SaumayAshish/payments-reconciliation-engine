package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToleranceTierTest {

    private static final MatchingConfig DEFAULTS = MatchingConfig.defaults();
    private static final LocalDate FRIDAY = Lines.FRIDAY;
    private static final LocalDate MONDAY = FRIDAY.plusDays(3);

    @Test
    void scoresFallWithBusinessDaysApartAcrossTheDefaultWindow() {
        assertEquals(960, scoreAtDistance(FRIDAY, MONDAY));
        assertEquals(930, scoreAtDistance(FRIDAY, MONDAY.plusDays(1)));
        assertEquals(900, scoreAtDistance(FRIDAY, MONDAY.plusDays(2)));
    }

    @Test
    void reasonNamesTheDistance() {
        MatchableLine statement = Lines.statement(0, "INV1", 125050, "GBP", MONDAY.plusDays(1));
        MatchableLine entry = Lines.ledger(0, "INV1", 125050, "GBP", FRIDAY);

        ScoredPair pair = ToleranceTier.candidates(List.of(statement), List.of(entry), DEFAULTS).get(0);

        assertEquals(MatchTier.TOLERANCE, pair.tier());
        assertEquals(930, pair.score().milli());
        assertTrue(pair.reason().contains("2 business day"));
    }

    @Test
    void datesBeyondTheWindowAreNotCandidates() {
        MatchableLine statement = Lines.statement(0, "INV1", 125050, "GBP", FRIDAY.plusDays(6));
        MatchableLine entry = Lines.ledger(0, "INV1", 125050, "GBP", FRIDAY);
        assertEquals(List.of(), ToleranceTier.candidates(List.of(statement), List.of(entry), DEFAULTS));
    }

    @Test
    void aWiderWindowAdmitsMoreDistanceWithItsOwnFloor() {
        MatchingConfig wide = new MatchingConfig(950, 700, 5, 10, 8, 600, 600);
        MatchableLine statement = Lines.statement(0, "INV1", 125050, "GBP", FRIDAY.plusDays(7));
        MatchableLine entry = Lines.ledger(0, "INV1", 125050, "GBP", FRIDAY);

        List<ScoredPair> pairs = ToleranceTier.candidates(List.of(statement), List.of(entry), wide);

        assertEquals(1, pairs.size());
        assertEquals(900, pairs.get(0).score().milli());
    }

    @Test
    void aSaturdayAndTheFollowingMondayAreZeroBusinessDaysApart() {
        assertEquals(990, scoreAtDistance(FRIDAY.plusDays(1), MONDAY));
        assertEquals(990, scoreAtDistance(FRIDAY.plusDays(1), FRIDAY.plusDays(2)));
    }

    @Test
    void identicalValueDatesBelongToTheExactTierNotThisOne() {
        MatchableLine statement = Lines.statement(0, "INV1", 125050, "GBP", FRIDAY);
        MatchableLine entry = Lines.ledger(0, "INV1", 125050, "GBP", FRIDAY);
        assertEquals(List.of(), ToleranceTier.candidates(List.of(statement), List.of(entry), DEFAULTS));
    }

    @Test
    void referenceAmountCurrencyAndAccountMustStillMatch() {
        MatchableLine statement = Lines.statement(0, "INV1", 125050, "GBP", MONDAY);
        assertNone(statement, Lines.ledger(0, "INV2", 125050, "GBP", FRIDAY));
        assertNone(statement, Lines.ledger(0, "INV1", 125051, "GBP", FRIDAY));
        assertNone(statement, Lines.ledger(0, "INV1", 125050, "USD", FRIDAY));
        assertNone(statement, Lines.ledger(0, "INV1", -125050, "GBP", FRIDAY));
    }

    @Test
    void scoreFormulaRoundsHalfUpAndStaysInsideTheTierRange() {
        assertEquals(967, ToleranceTier.scoreFor(1, 4));
        assertEquals(972, ToleranceTier.scoreFor(1, 5));
        for (int window = 1; window <= MatchingConfig.MAX_DATE_WINDOW; window++) {
            int previous = Integer.MAX_VALUE;
            for (int distance = 0; distance <= window; distance++) {
                int milli = ToleranceTier.scoreFor(distance, window);
                assertTrue(MatchTier.TOLERANCE.allows(MatchScore.ofMilli(milli)), "window " + window);
                assertTrue(milli <= previous, "score must not rise with distance");
                previous = milli;
            }
            assertEquals(MatchTier.TOLERANCE.floorMilli(), ToleranceTier.scoreFor(window, window));
            assertEquals(MatchTier.TOLERANCE.ceilingMilli(), ToleranceTier.scoreFor(0, window));
        }
    }

    private static int scoreAtDistance(LocalDate statementDate, LocalDate ledgerDate) {
        MatchableLine statement = Lines.statement(0, "INV1", 125050, "GBP", statementDate);
        MatchableLine entry = Lines.ledger(0, "INV1", 125050, "GBP", ledgerDate);
        List<ScoredPair> pairs = ToleranceTier.candidates(List.of(statement), List.of(entry), DEFAULTS);
        assertEquals(1, pairs.size());
        return pairs.get(0).score().milli();
    }

    private static void assertNone(MatchableLine statement, MatchableLine entry) {
        assertEquals(List.of(), ToleranceTier.candidates(List.of(statement), List.of(entry), DEFAULTS));
    }
}