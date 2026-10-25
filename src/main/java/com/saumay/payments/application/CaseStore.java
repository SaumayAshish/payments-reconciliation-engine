package com.saumay.payments.application;

import com.saumay.payments.domain.cases.CaseActivityType;
import com.saumay.payments.domain.cases.CasePriority;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.CaseTeam;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Persistence port for working a case (ADR-0007). */
public interface CaseStore {

    Optional<CaseRecord> find(long caseId);

    /**
     * Applies the change only if the stored version still equals {@code expectedVersion}: the case row, its new
     * timeline entry and any assignment row are written in one transaction, or not at all.
     *
     * @return false when the version no longer matches, in which case nothing was written
     */
    boolean apply(CaseChange change, Instant at);

    /** Adds a comment and its timeline entry; returns the comment's ordinal on that case. */
    int addComment(long caseId, String author, String body, Instant at);

    /** The new state of a case plus the history rows that explain it. */
    record CaseChange(long caseId, long expectedVersion, CaseStatus status, String owner, CaseTeam team,
            CasePriority priority, String routingRule, Activity activity, Assignment assignment) {
        public CaseChange {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(priority, "priority");
            Objects.requireNonNull(routingRule, "routingRule");
            Objects.requireNonNull(activity, "activity");
        }
    }

    /** One timeline entry. A status pair is both set or both empty, as the database requires. */
    record Activity(CaseActivityType type, String actor, CaseStatus from, CaseStatus to, String detail) {
        public Activity {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(actor, "actor");
            if ((from == null) != (to == null)) {
                throw new IllegalArgumentException("A status change needs both a from and a to status");
            }
        }
    }

    /** One owner-history row (AC-07.3). */
    record Assignment(String owner, CaseTeam team, String assignedBy, String reason) {
        public Assignment {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(assignedBy, "assignedBy");
        }
    }
}