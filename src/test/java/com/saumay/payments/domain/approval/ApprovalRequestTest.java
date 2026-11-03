package com.saumay.payments.domain.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ApprovalRequestTest {

    private static final Instant MADE = Instant.parse("2026-10-09T10:00:00Z");
    private static final Instant DECIDED = Instant.parse("2026-10-09T11:00:00Z");

    private static ApprovalRequest pending() {
        return ApprovalRequest.pending(7, ControlledAction.WRITE_OFF, 2_500_000, "GBP", "  stale item ", " carol ", MADE, 0);
    }

    @Test
    void aNewRequestIsPendingWithNoDecisionAndTidyText() {
        ApprovalRequest request = pending();
        assertEquals(ApprovalStatus.PENDING, request.status());
        assertNull(request.checker());
        assertNull(request.decidedAt());
        assertEquals("stale item", request.reason());
        assertEquals("carol", request.maker());
    }

    @Test
    void aDifferentCheckerApprovesAndTheOriginalIsUntouched() {
        ApprovalRequest before = pending();
        ApprovalRequest after = before.approve("dave", DECIDED, null);
        assertEquals(ApprovalStatus.APPROVED, after.status());
        assertEquals("dave", after.checker());
        assertEquals(DECIDED, after.decidedAt());
        assertEquals(ApprovalStatus.PENDING, before.status());
    }

    @Test
    void theMakerCannotApproveOrRejectTheirOwnRequest() {
        ApprovalRequest request = pending();
        assertThrows(SeparationOfDutiesException.class, () -> request.approve("CAROL", DECIDED, null));
        assertThrows(SeparationOfDutiesException.class, () -> request.reject("carol", DECIDED, "no"));
    }

    @Test
    void aRejectionNeedsAReason() {
        ApprovalRequest request = pending();
        assertThrows(IllegalArgumentException.class, () -> request.reject("dave", DECIDED, " "));
        assertThrows(IllegalArgumentException.class, () -> request.reject("dave", DECIDED, null));
        assertEquals(ApprovalStatus.REJECTED, request.reject("dave", DECIDED, "not supported by evidence").status());
    }

    @Test
    void aDecidedRequestCannotBeDecidedAgain() {
        ApprovalRequest approved = pending().approve("dave", DECIDED, null);
        assertThrows(ApprovalAlreadyDecidedException.class, () -> approved.approve("erin", DECIDED, null));
        assertThrows(ApprovalAlreadyDecidedException.class, () -> approved.reject("erin", DECIDED, "late"));
    }

    @Test
    void alreadyDecidedIsReportedBeforeSeparationOfDuties() {
        ApprovalRequest approved = pending().approve("dave", DECIDED, null);
        assertThrows(ApprovalAlreadyDecidedException.class, () -> approved.approve("carol", DECIDED, null));
    }

    @Test
    void invalidStatesCannotBeBuilt() {
        assertThrows(IllegalArgumentException.class, () -> new ApprovalRequest(null, 7, ControlledAction.WRITE_OFF,
                100, "GBP", "r", "carol", MADE, 0, ApprovalStatus.PENDING, "dave", null, null));
        assertThrows(SeparationOfDutiesException.class, () -> new ApprovalRequest(1L, 7, ControlledAction.WRITE_OFF,
                100, "GBP", "r", "carol", MADE, 0, ApprovalStatus.APPROVED, "Carol", DECIDED, null));
        assertThrows(IllegalArgumentException.class, () -> ApprovalRequest.pending(7, ControlledAction.ADJUSTMENT, 0,
                "GBP", "r", "carol", MADE, 0));
        assertThrows(IllegalArgumentException.class, () -> ApprovalRequest.pending(7, ControlledAction.ADJUSTMENT, 5,
                "gbp", "r", "carol", MADE, 0));
        assertThrows(IllegalArgumentException.class, () -> ApprovalRequest.pending(7, ControlledAction.ADJUSTMENT, 5,
                "GBP", " ", "carol", MADE, 0));
    }

    @Test
    void eachControlledActionMapsToItsResolution() {
        assertEquals("WRITE_OFF", ControlledAction.WRITE_OFF.resolution().name());
        assertEquals("ADJUSTMENT", ControlledAction.ADJUSTMENT.resolution().name());
    }
}