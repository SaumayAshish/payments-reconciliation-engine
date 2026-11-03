package com.saumay.payments.domain.approval;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MakerCheckerRuleTest {

    @Test
    void theSamePersonIsRefused() {
        assertThrows(SeparationOfDutiesException.class, () -> MakerCheckerRule.requireDistinct("alice", "alice"));
    }

    @Test
    void caseAndSurroundingSpacesDoNotMakeSomeoneElse() {
        assertTrue(MakerCheckerRule.sameActor("alice", "ALICE"));
        assertTrue(MakerCheckerRule.sameActor(" Alice ", "alice"));
        assertThrows(SeparationOfDutiesException.class, () -> MakerCheckerRule.requireDistinct("carol", " CAROL"));
    }

    @Test
    void twoDifferentPeopleAreAccepted() {
        assertFalse(MakerCheckerRule.sameActor("carol", "dave"));
        assertDoesNotThrow(() -> MakerCheckerRule.requireDistinct("carol", "dave"));
    }

    @Test
    void theRefusalNamesNobody() {
        SeparationOfDutiesException refusal =
                assertThrows(SeparationOfDutiesException.class, () -> MakerCheckerRule.requireDistinct("carol", "carol"));
        assertFalse(refusal.getMessage().contains("carol"));
    }
}