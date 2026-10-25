package com.saumay.payments.domain.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class CaseStatusTest {

    // FRD-07, written out here independently of the implementation.
    private static final Set<String> FRD_07 = Set.of(
            "OPEN>ASSIGNED", "OPEN>ESCALATED",
            "ASSIGNED>INVESTIGATING", "ASSIGNED>ESCALATED",
            "INVESTIGATING>RESOLVED", "INVESTIGATING>ESCALATED",
            "ESCALATED>INVESTIGATING", "ESCALATED>ASSIGNED");

    @Test
    void exactlyEightOfTheTwentyFiveOrderedPairsAreLegal() {
        Set<String> legal = new TreeSet<>();
        int pairs = 0;
        for (CaseStatus from : CaseStatus.values()) {
            for (CaseStatus to : CaseStatus.values()) {
                pairs++;
                if (from.canMoveTo(to)) {
                    legal.add(from + ">" + to);
                }
            }
        }
        assertEquals(25, pairs);
        assertEquals(8, legal.size());
        assertEquals(new TreeSet<>(FRD_07), legal);
    }

    @Test
    void theExamplesFromAc072AreRefused() {
        assertFalse(CaseStatus.RESOLVED.canMoveTo(CaseStatus.OPEN));
        assertFalse(CaseStatus.OPEN.canMoveTo(CaseStatus.RESOLVED));
        assertFalse(CaseStatus.OPEN.canMoveTo(CaseStatus.INVESTIGATING));
    }

    @Test
    void aStateNeverMovesToItself() {
        for (CaseStatus status : CaseStatus.values()) {
            assertFalse(status.canMoveTo(status), status.toString());
        }
    }

    @Test
    void nothingEverMovesBackToOpen() {
        for (CaseStatus status : CaseStatus.values()) {
            assertFalse(status.canMoveTo(CaseStatus.OPEN), status.toString());
        }
    }

    @Test
    void resolvedIsTheOnlyTerminalState() {
        for (CaseStatus status : CaseStatus.values()) {
            assertEquals(status == CaseStatus.RESOLVED, status.isTerminal(), status.toString());
        }
    }

    @Test
    void anIllegalMoveNamesBothStates() {
        IllegalCaseTransitionException e = assertThrows(IllegalCaseTransitionException.class,
                () -> CaseStatus.RESOLVED.requireMoveTo(CaseStatus.OPEN));
        assertEquals(CaseStatus.RESOLVED, e.from());
        assertEquals(CaseStatus.OPEN, e.to());
        assertTrue(e.getMessage().contains("RESOLVED -> OPEN"), e.getMessage());
    }

    @Test
    void aLegalMoveReturnsNormally() {
        CaseStatus.OPEN.requireMoveTo(CaseStatus.ASSIGNED);
        CaseStatus.ESCALATED.requireMoveTo(CaseStatus.ASSIGNED);
    }

    @Test
    void theLegalTargetsCannotBeUsedToChangeTheTable() {
        CaseStatus.OPEN.legalTargets().clear();
        assertTrue(CaseStatus.OPEN.canMoveTo(CaseStatus.ASSIGNED));
    }
}