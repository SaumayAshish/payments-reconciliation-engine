package com.saumay.payments.domain.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CasePriorityAndKindTest {

    @Test
    void escalationRaisesPriorityOneLevelAndStopsAtP1() {
        assertEquals(CasePriority.P3, CasePriority.P4.raisedOneLevel());
        assertEquals(CasePriority.P2, CasePriority.P3.raisedOneLevel());
        assertEquals(CasePriority.P1, CasePriority.P2.raisedOneLevel());
        assertEquals(CasePriority.P1, CasePriority.P1.raisedOneLevel());
    }

    @Test
    void onlyLowConfidenceAndAmbiguousTieAreReviewCases() {
        Set<CaseKind> review = Set.of(CaseKind.LOW_CONFIDENCE, CaseKind.AMBIGUOUS_TIE);
        for (CaseKind kind : CaseKind.values()) {
            CaseClass expected = review.contains(kind) ? CaseClass.REVIEW : CaseClass.BREAK;
            assertEquals(expected, kind.caseClass(), kind.toString());
        }
    }

    @Test
    void theSevenKindsOfFrd06Exist() {
        assertEquals(7, CaseKind.values().length);
        assertEquals(6, CaseTeam.values().length);
    }
}