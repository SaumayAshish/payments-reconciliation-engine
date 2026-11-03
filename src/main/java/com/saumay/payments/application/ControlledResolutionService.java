package com.saumay.payments.application;

import com.saumay.payments.application.ApprovalStore.Approval;
import com.saumay.payments.application.ApprovalStore.CaseFacts;
import com.saumay.payments.application.ApprovalStore.DeniedAttempt;
import com.saumay.payments.application.ApprovalStore.Rejection;
import com.saumay.payments.application.ApprovalStore.SubmitResult;
import com.saumay.payments.application.ApprovalStore.Submission;
import com.saumay.payments.application.CaseMatchStore.CaseState;
import com.saumay.payments.application.CaseMatchStore.Resolution;
import com.saumay.payments.application.CaseMatchStore.SubjectLine;
import com.saumay.payments.domain.approval.ApprovalAlreadyDecidedException;
import com.saumay.payments.domain.approval.ApprovalRequest;
import com.saumay.payments.domain.approval.ApprovalThreshold;
import com.saumay.payments.domain.approval.ControlledAction;
import com.saumay.payments.domain.approval.SeparationOfDutiesException;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.IllegalCaseTransitionException;
import com.saumay.payments.domain.cases.MatchLine;
import com.saumay.payments.domain.cases.MatchLine.Side;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Write-offs and adjustments (FRD-08, ADR-0011). At or below the threshold the action resolves the case at once; above
 * it the action waits for a second person who is not the maker. The case only changes when the checker approves, and
 * then the approval, the resolution and the claims commit together.
 */
public final class ControlledResolutionService {

    private static final int MAX_NAME = 100;
    private static final int MAX_REASON = 1_000;

    private final ApprovalStore approvals;
    private final CaseMatchStore cases;
    private final ApprovalThreshold threshold;
    private final long configVersion;
    private final Clock clock;

    public ControlledResolutionService(ApprovalStore approvals, CaseMatchStore cases, ApprovalThreshold threshold,
            long configVersion, Clock clock) {
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.threshold = Objects.requireNonNull(threshold, "threshold");
        this.configVersion = configVersion;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Either the case was resolved (resolved is set) or an approval is waiting (approvalId is set). */
    public record Outcome(CaseState resolved, Long approvalId) {
        public boolean pendingApproval() {
            return approvalId != null;
        }
    }

    public Outcome resolve(long caseId, long expectedVersion, String actor, ControlledAction action, String reason) {
        Objects.requireNonNull(action, "action");
        String by = requiredText(actor, MAX_NAME, "actor");
        String why = requiredText(reason, MAX_REASON, "reason");
        CaseFacts facts = approvals.findCase(caseId).orElseThrow(() -> caseNotFound(caseId));
        if (facts.version() != expectedVersion) {
            throw conflict(caseId);
        }
        requireResolvable(facts.status());
        if (approvals.findPendingForCase(caseId).isPresent()) {
            throw pending(caseId);
        }
        List<MatchLine> lines = subjectLines(caseId);
        Instant now = clock.instant();

        if (!threshold.requiresApproval(facts.currency(), facts.amountMinor())) {
            Resolution resolution = new Resolution(caseId, expectedVersion, action.resolution(), by, why, lines,
                    CaseAudit.resolved(caseId, facts.status(), facts.version(), action.resolution(), why, lines));
            switch (cases.resolve(resolution, now)) {
                case RESOLVED -> {
                }
                case VERSION_CONFLICT -> throw conflict(caseId);
                case LINE_ALREADY_CLAIMED -> throw claimed();
            }
            return new Outcome(cases.findState(caseId).orElseThrow(() -> caseNotFound(caseId)), null);
        }

        ApprovalRequest request = ApprovalRequest.pending(caseId, action, facts.amountMinor(), facts.currency(), why,
                by, now, configVersion);
        SubmitResult result = approvals.submit(
                new Submission(request, expectedVersion, ApprovalAudit.requested(request)), now);
        switch (result.outcome()) {
            case SUBMITTED -> {
                return new Outcome(null, result.id());
            }
            case VERSION_CONFLICT -> throw conflict(caseId);
            case ALREADY_PENDING -> throw pending(caseId);
        }
        throw new IllegalStateException("unreachable");
    }

    public ApprovalRequest approve(long approvalId, String checker, String note) {
        String by = requiredText(checker, MAX_NAME, "checker");
        String text = optionalText(note, MAX_REASON, "note");
        ApprovalRequest request = load(approvalId);
        Instant now = clock.instant();
        ApprovalRequest decided = decide(request, by, now, () -> request.approve(by, now, text));

        CaseFacts facts = approvals.findCase(request.caseId()).orElseThrow(() -> caseNotFound(request.caseId()));
        requireResolvable(facts.status());
        List<MatchLine> lines = subjectLines(request.caseId());
        Resolution resolution = new Resolution(request.caseId(), facts.version(), request.action().resolution(), by,
                request.reason(), lines, CaseAudit.resolved(request.caseId(), facts.status(), facts.version(),
                        request.action().resolution(), request.reason(), lines));
        switch (approvals.approve(new Approval(decided, resolution, ApprovalAudit.decided(request, decided)), now)) {
            case DECIDED -> {
            }
            case ALREADY_DECIDED -> throw alreadyDecided();
            case VERSION_CONFLICT -> throw conflict(request.caseId());
            case LINE_ALREADY_CLAIMED -> throw claimed();
        }
        return load(approvalId);
    }

    public ApprovalRequest reject(long approvalId, String checker, String note) {
        String by = requiredText(checker, MAX_NAME, "checker");
        String why = requiredText(note, MAX_REASON, "note");
        ApprovalRequest request = load(approvalId);
        Instant now = clock.instant();
        ApprovalRequest decided = decide(request, by, now, () -> request.reject(by, now, why));
        switch (approvals.reject(new Rejection(decided, ApprovalAudit.decided(request, decided)), now)) {
            case DECIDED -> {
            }
            case ALREADY_DECIDED -> throw alreadyDecided();
            case VERSION_CONFLICT, LINE_ALREADY_CLAIMED -> throw new IllegalStateException("not possible for a rejection");
        }
        return load(approvalId);
    }

    /** Applies the decision to the value. A maker deciding their own request is refused and the attempt recorded. */
    private ApprovalRequest decide(ApprovalRequest request, String by, Instant now, Supplier<ApprovalRequest> decision) {
        try {
            return decision.get();
        } catch (SeparationOfDutiesException e) {
            approvals.recordDenied(new DeniedAttempt(request.id(), by, ApprovalAudit.denied(request, by)), now);
            throw new ApprovalRejectedException(ApprovalErrorCode.SEPARATION_OF_DUTIES_VIOLATION, e.getMessage());
        } catch (ApprovalAlreadyDecidedException e) {
            throw new ApprovalRejectedException(ApprovalErrorCode.APPROVAL_ALREADY_DECIDED, e.getMessage());
        }
    }

    private ApprovalRequest load(long approvalId) {
        return approvals.find(approvalId).orElseThrow(() -> new ApprovalRejectedException(
                ApprovalErrorCode.APPROVAL_NOT_FOUND, "No approval request exists with id " + approvalId));
    }

    private List<MatchLine> subjectLines(long caseId) {
        List<SubjectLine> subjects = cases.subjectLines(caseId);
        if (subjects.isEmpty()) {
            throw new ApprovalRejectedException(ApprovalErrorCode.CONTROLLED_ACTION_NOT_ALLOWED,
                    "A write-off or adjustment needs a case that came from a reconciliation run");
        }
        List<Long> statements = new ArrayList<>();
        List<Long> ledger = new ArrayList<>();
        for (SubjectLine subject : subjects) {
            (subject.side() == Side.STATEMENT ? statements : ledger).add(subject.seq());
        }
        List<MatchLine> found = cases.loadLines(statements, ledger);
        if (found.size() != subjects.size()) {
            throw new CaseRejectedException(CaseErrorCode.MATCH_LINE_NOT_FOUND, "One or more of the lines do not exist");
        }
        return found;
    }

    private static void requireResolvable(CaseStatus status) {
        try {
            status.requireMoveTo(CaseStatus.RESOLVED);
        } catch (IllegalCaseTransitionException e) {
            throw new CaseRejectedException(CaseErrorCode.ILLEGAL_STATE_TRANSITION,
                    "A case can only be resolved while it is INVESTIGATING; this one is " + status);
        }
    }

    private static String requiredText(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required");
        }
        return bounded(value.trim(), max, field);
    }

    private static String optionalText(String value, int max, String field) {
        return value == null || value.isBlank() ? null : bounded(value.trim(), max, field);
    }

    private static String bounded(String text, int max, String field) {
        if (text.length() > max) {
            throw invalid(field + " must be at most " + max + " characters");
        }
        return text;
    }

    private static CaseRejectedException invalid(String message) {
        return new CaseRejectedException(CaseErrorCode.CASE_REQUEST_INVALID, message);
    }

    private static CaseRejectedException caseNotFound(long caseId) {
        return new CaseRejectedException(CaseErrorCode.CASE_NOT_FOUND, "No case exists with id " + caseId);
    }

    private static CaseRejectedException conflict(long caseId) {
        return new CaseRejectedException(CaseErrorCode.CASE_VERSION_CONFLICT,
                "Case " + caseId + " was changed by someone else; reload it and try again");
    }

    private static CaseRejectedException claimed() {
        return new CaseRejectedException(CaseErrorCode.MATCH_LINE_ALREADY_CLAIMED,
                "One or more of the lines already belong to an active match");
    }

    private static ApprovalRejectedException pending(long caseId) {
        return new ApprovalRejectedException(ApprovalErrorCode.APPROVAL_PENDING,
                "Case " + caseId + " already has a pending approval request");
    }

    private static ApprovalRejectedException alreadyDecided() {
        return new ApprovalRejectedException(ApprovalErrorCode.APPROVAL_ALREADY_DECIDED,
                "The approval request has already been decided");
    }
}