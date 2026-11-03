package com.saumay.payments.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.application.ApprovalStore.Approval;
import com.saumay.payments.application.ApprovalStore.CaseFacts;
import com.saumay.payments.application.ApprovalStore.DecisionOutcome;
import com.saumay.payments.application.ApprovalStore.DeniedAttempt;
import com.saumay.payments.application.ApprovalStore.Rejection;
import com.saumay.payments.application.ApprovalStore.Submission;
import com.saumay.payments.application.ApprovalStore.SubmitOutcome;
import com.saumay.payments.application.ApprovalStore.SubmitResult;
import com.saumay.payments.application.CaseMatchStore.ActiveMatch;
import com.saumay.payments.application.CaseMatchStore.CaseState;
import com.saumay.payments.application.CaseMatchStore.NewUnmatchCase;
import com.saumay.payments.application.CaseMatchStore.Resolution;
import com.saumay.payments.application.CaseMatchStore.ResolveOutcome;
import com.saumay.payments.application.CaseMatchStore.SubjectLine;
import com.saumay.payments.domain.approval.ApprovalRequest;
import com.saumay.payments.domain.approval.ApprovalStatus;
import com.saumay.payments.domain.approval.ApprovalThreshold;
import com.saumay.payments.domain.approval.ControlledAction;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.MatchLine;
import com.saumay.payments.domain.cases.MatchLine.Side;
import com.saumay.payments.domain.cases.ResolutionType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ControlledResolutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final long OVER = ApprovalThreshold.DEFAULT_MINOR + 1;

    private FakeApprovals approvals;
    private FakeCases cases;
    private ControlledResolutionService service;

    @BeforeEach
    void setUp() {
        approvals = new FakeApprovals();
        cases = new FakeCases();
        service = new ControlledResolutionService(approvals, cases, ApprovalThreshold.defaults(), 0L,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String codeOf(Executable action) {
        Throwable t = assertThrows(RuntimeException.class, action);
        if (t instanceof ApprovalRejectedException a) {
            return a.code().name();
        }
        if (t instanceof CaseRejectedException c) {
            return c.code().name();
        }
        throw new AssertionError("unexpected " + t);
    }

    private ApprovalRequest pendingFor(String maker) {
        ApprovalRequest r = ApprovalRequest.pending(7, ControlledAction.WRITE_OFF, OVER, "GBP", "stale item", maker,
                NOW, 0);
        return withId(r, 1L);
    }

    private static ApprovalRequest withId(ApprovalRequest r, long id) {
        return new ApprovalRequest(id, r.caseId(), r.action(), r.amountMinor(), r.currency(), r.reason(), r.maker(),
                r.requestedAt(), r.configVersion(), r.status(), r.checker(), r.decidedAt(), r.decisionNote());
    }

    @Test
    void anAmountAtTheThresholdResolvesWithoutApproval() {
        approvals.facts = new CaseFacts(CaseStatus.INVESTIGATING, 3, ApprovalThreshold.DEFAULT_MINOR, "GBP");
        ControlledResolutionService.Outcome outcome =
                service.resolve(7, 3, "alice", ControlledAction.WRITE_OFF, "stale item");
        assertFalse(outcome.pendingApproval());
        assertNotNull(cases.resolved);
        assertEquals(ResolutionType.WRITE_OFF, cases.resolved.type());
        assertEquals(2, cases.resolved.lines().size());
        assertTrue(approvals.submitted.isEmpty());
    }

    @Test
    void anAmountAboveTheThresholdWaitsForApprovalAndChangesNothing() {
        approvals.facts = new CaseFacts(CaseStatus.INVESTIGATING, 3, OVER, "GBP");
        ControlledResolutionService.Outcome outcome =
                service.resolve(7, 3, "alice", ControlledAction.WRITE_OFF, "stale item");
        assertTrue(outcome.pendingApproval());
        assertNull(cases.resolved);
        assertEquals(1, approvals.submitted.size());
        ApprovalRequest request = approvals.submitted.get(0).request();
        assertEquals(ApprovalStatus.PENDING, request.status());
        assertEquals("alice", request.maker());
        assertEquals(OVER, request.amountMinor());
    }

    @Test
    void anAdjustmentIsControlledLikeAWriteOff() {
        approvals.facts = new CaseFacts(CaseStatus.INVESTIGATING, 3, OVER, "GBP");
        service.resolve(7, 3, "alice", ControlledAction.ADJUSTMENT, "fx rounding");
        assertEquals(ControlledAction.ADJUSTMENT, approvals.submitted.get(0).request().action());
    }

    @Test
    void aCaseWithAPendingRequestCannotBeActedOnAgain() {
        approvals.facts = new CaseFacts(CaseStatus.INVESTIGATING, 3, OVER, "GBP");
        approvals.requests.put(1L, pendingFor("alice"));
        assertEquals("APPROVAL_PENDING",
                codeOf(() -> service.resolve(7, 3, "bob", ControlledAction.WRITE_OFF, "again")));
        assertTrue(approvals.submitted.isEmpty());
    }

    @Test
    void aStaleVersionIsRefused() {
        assertEquals("CASE_VERSION_CONFLICT",
                codeOf(() -> service.resolve(7, 2, "alice", ControlledAction.WRITE_OFF, "x")));
    }

    @Test
    void onlyAnInvestigatingCaseCanBeResolved() {
        approvals.facts = new CaseFacts(CaseStatus.ASSIGNED, 3, OVER, "GBP");
        assertEquals("ILLEGAL_STATE_TRANSITION",
                codeOf(() -> service.resolve(7, 3, "alice", ControlledAction.WRITE_OFF, "x")));
    }

    @Test
    void aCaseThatDidNotComeFromARunCannotBeWrittenOff() {
        cases.subjects = List.of();
        assertEquals("CONTROLLED_ACTION_NOT_ALLOWED",
                codeOf(() -> service.resolve(7, 3, "alice", ControlledAction.WRITE_OFF, "x")));
    }

    @Test
    void aReasonIsRequired() {
        assertEquals("CASE_REQUEST_INVALID",
                codeOf(() -> service.resolve(7, 3, "alice", ControlledAction.WRITE_OFF, "  ")));
    }

    @Test
    void theMakerCannotApproveTheirOwnRequestAndTheAttemptIsRecorded() {
        approvals.requests.put(1L, pendingFor("carol"));
        assertEquals("SEPARATION_OF_DUTIES_VIOLATION", codeOf(() -> service.approve(1, "CAROL", null)));
        assertEquals(1, approvals.denied.size());
        assertEquals("APPROVAL_DENIED", approvals.denied.get(0).audit().action());
        assertTrue(approvals.approved.isEmpty());
        assertNull(cases.resolved);
    }

    @Test
    void theMakerCannotRejectTheirOwnRequestEither() {
        approvals.requests.put(1L, pendingFor("carol"));
        assertEquals("SEPARATION_OF_DUTIES_VIOLATION", codeOf(() -> service.reject(1, "carol", "no")));
        assertEquals(1, approvals.denied.size());
        assertTrue(approvals.rejected.isEmpty());
    }

    @Test
    void aDifferentCheckerApprovesAndTheResolutionCarriesTheRequestsAction() {
        approvals.requests.put(1L, pendingFor("carol"));
        ApprovalRequest decided = service.approve(1, "dave", "evidence seen");
        assertEquals(ApprovalStatus.APPROVED, decided.status());
        assertEquals(1, approvals.approved.size());
        Resolution resolution = approvals.approved.get(0).resolution();
        assertEquals(ResolutionType.WRITE_OFF, resolution.type());
        assertEquals("dave", resolution.actor());
        assertEquals("stale item", resolution.reason());
        assertEquals(3, resolution.expectedVersion());
        assertEquals("APPROVAL_APPROVED", approvals.approved.get(0).audit().action());
        assertTrue(approvals.denied.isEmpty());
    }

    @Test
    void aRejectionNeedsAReasonAndLeavesTheCaseUntouched() {
        approvals.requests.put(1L, pendingFor("carol"));
        assertEquals("CASE_REQUEST_INVALID", codeOf(() -> service.reject(1, "dave", " ")));
        ApprovalRequest decided = service.reject(1, "dave", "no supporting evidence");
        assertEquals(ApprovalStatus.REJECTED, decided.status());
        assertEquals("APPROVAL_REJECTED", approvals.rejected.get(0).audit().action());
        assertNull(cases.resolved);
    }

    @Test
    void aDecidedRequestCannotBeDecidedAgain() {
        approvals.requests.put(1L, pendingFor("carol"));
        service.approve(1, "dave", null);
        assertEquals("APPROVAL_ALREADY_DECIDED", codeOf(() -> service.approve(1, "erin", null)));
        assertEquals("APPROVAL_ALREADY_DECIDED", codeOf(() -> service.reject(1, "erin", "late")));
    }

    @Test
    void anUnknownRequestIsNotFound() {
        assertEquals("APPROVAL_NOT_FOUND", codeOf(() -> service.approve(99, "dave", null)));
    }

    @Test
    void aLineClaimedMeanwhileFailsTheApprovalAndLeavesItPending() {
        approvals.requests.put(1L, pendingFor("carol"));
        approvals.decisionOutcome = DecisionOutcome.LINE_ALREADY_CLAIMED;
        assertEquals("MATCH_LINE_ALREADY_CLAIMED", codeOf(() -> service.approve(1, "dave", null)));
        assertEquals(ApprovalStatus.PENDING, approvals.requests.get(1L).status());
    }

    @Test
    void aCaseThatMovedOnSinceTheRequestFailsTheApproval() {
        approvals.requests.put(1L, pendingFor("carol"));
        approvals.facts = new CaseFacts(CaseStatus.ESCALATED, 4, OVER, "GBP");
        assertEquals("ILLEGAL_STATE_TRANSITION", codeOf(() -> service.approve(1, "dave", null)));
        assertTrue(approvals.approved.isEmpty());
    }

    private static final class FakeApprovals implements ApprovalStore {
        CaseFacts facts = new CaseFacts(CaseStatus.INVESTIGATING, 3, OVER, "GBP");
        final Map<Long, ApprovalRequest> requests = new HashMap<>();
        final List<Submission> submitted = new ArrayList<>();
        final List<Approval> approved = new ArrayList<>();
        final List<Rejection> rejected = new ArrayList<>();
        final List<DeniedAttempt> denied = new ArrayList<>();
        SubmitOutcome submitOutcome = SubmitOutcome.SUBMITTED;
        DecisionOutcome decisionOutcome = DecisionOutcome.DECIDED;

        @Override
        public Optional<CaseFacts> findCase(long caseId) {
            return Optional.of(facts);
        }

        @Override
        public Optional<ApprovalRequest> find(long approvalId) {
            return Optional.ofNullable(requests.get(approvalId));
        }

        @Override
        public Optional<ApprovalRequest> findPendingForCase(long caseId) {
            return requests.values().stream()
                    .filter(r -> r.caseId() == caseId && r.status() == ApprovalStatus.PENDING).findFirst();
        }

        @Override
        public SubmitResult submit(Submission submission, Instant at) {
            submitted.add(submission);
            return new SubmitResult(submitOutcome, 1);
        }

        @Override
        public DecisionOutcome approve(Approval approval, Instant at) {
            approved.add(approval);
            if (decisionOutcome == DecisionOutcome.DECIDED) {
                requests.put(approval.decided().id(), approval.decided());
            }
            return decisionOutcome;
        }

        @Override
        public DecisionOutcome reject(Rejection rejection, Instant at) {
            rejected.add(rejection);
            if (decisionOutcome == DecisionOutcome.DECIDED) {
                requests.put(rejection.decided().id(), rejection.decided());
            }
            return decisionOutcome;
        }

        @Override
        public void recordDenied(DeniedAttempt attempt, Instant at) {
            denied.add(attempt);
        }
    }

    private static final class FakeCases implements CaseMatchStore {
        CaseState state = new CaseState(CaseStatus.INVESTIGATING, 3);
        List<SubjectLine> subjects = List.of(new SubjectLine(Side.STATEMENT, 11), new SubjectLine(Side.LEDGER, 21));
        Resolution resolved;
        ResolveOutcome outcome = ResolveOutcome.RESOLVED;

        @Override
        public Optional<CaseState> findState(long caseId) {
            return Optional.of(state);
        }

        @Override
        public List<MatchLine> loadLines(List<Long> statementSeqs, List<Long> ledgerSeqs) {
            List<MatchLine> lines = new ArrayList<>();
            for (Long seq : statementSeqs) {
                lines.add(new MatchLine(Side.STATEMENT, seq, 3_000_000, "GBP"));
            }
            for (Long seq : ledgerSeqs) {
                lines.add(new MatchLine(Side.LEDGER, seq, 3_000_000, "GBP"));
            }
            return lines;
        }

        @Override
        public List<SubjectLine> subjectLines(long caseId) {
            return subjects;
        }

        @Override
        public ResolveOutcome resolve(Resolution resolution, Instant at) {
            resolved = resolution;
            return outcome;
        }

        @Override
        public Optional<ActiveMatch> findActiveMatchOfCase(long caseId) {
            return Optional.empty();
        }

        @Override
        public Optional<ActiveMatch> findActiveMatchOfRunMatch(long runId, int matchOrdinal) {
            return Optional.empty();
        }

        @Override
        public OptionalLong unmatch(ActiveMatch match, NewUnmatchCase newCase, String actor, String reason,
                Instant at) {
            return OptionalLong.empty();
        }
    }
}