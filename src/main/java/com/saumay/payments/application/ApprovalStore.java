package com.saumay.payments.application;

import com.saumay.payments.application.CaseMatchStore.Resolution;
import com.saumay.payments.domain.approval.ApprovalRequest;
import com.saumay.payments.domain.cases.CaseStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongFunction;

/**
 * Persistence port for maker-checker approvals (ADR-0011). Each write is one transaction that also appends its audit
 * event as the last statement, so an approval and its evidence exist together or not at all.
 */
public interface ApprovalStore {

    Optional<CaseFacts> findCase(long caseId);

    Optional<ApprovalRequest> find(long approvalId);

    Optional<ApprovalRequest> findPendingForCase(long caseId);

    /** Writes the pending request, its timeline entry and its audit event. */
    SubmitResult submit(Submission submission, Instant at);

    /** Marks the request approved and resolves the case and claims its lines, all in one transaction. */
    DecisionOutcome approve(Approval approval, Instant at);

    DecisionOutcome reject(Rejection rejection, Instant at);

    /** Records a refused decision attempt in its own committed transaction. */
    void recordDenied(DeniedAttempt attempt, Instant at);

    enum SubmitOutcome {
        SUBMITTED,
        VERSION_CONFLICT,
        ALREADY_PENDING
    }

    enum DecisionOutcome {
        DECIDED,
        ALREADY_DECIDED,
        VERSION_CONFLICT,
        LINE_ALREADY_CLAIMED
    }

    record CaseFacts(CaseStatus status, long version, long amountMinor, String currency) {
        public CaseFacts {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(currency, "currency");
        }
    }

    record Submission(ApprovalRequest request, long expectedCaseVersion, LongFunction<AuditNote> audit) {
        public Submission {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(audit, "audit");
        }
    }

    record SubmitResult(SubmitOutcome outcome, long id) {
        public SubmitResult {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    record Approval(ApprovalRequest decided, Resolution resolution, AuditNote audit) {
        public Approval {
            Objects.requireNonNull(decided, "decided");
            Objects.requireNonNull(resolution, "resolution");
            Objects.requireNonNull(audit, "audit");
        }
    }

    record Rejection(ApprovalRequest decided, AuditNote audit) {
        public Rejection {
            Objects.requireNonNull(decided, "decided");
            Objects.requireNonNull(audit, "audit");
        }
    }

    record DeniedAttempt(long approvalId, String actor, AuditNote audit) {
        public DeniedAttempt {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(audit, "audit");
        }
    }
}