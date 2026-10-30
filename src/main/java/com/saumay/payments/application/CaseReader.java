package com.saumay.payments.application;

import com.saumay.payments.domain.cases.CaseActivityType;
import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CasePriority;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.CaseTeam;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read side of case management: the work queue and the full case view (FRD-12). It never changes anything. The
 * detail is read from one consistent snapshot, so a case never shows a status its own timeline has not reached.
 */
public interface CaseReader {

    /** Cases matching the filter with an id above {@code afterId}, in id order, at most {@code limit} of them. */
    CasePage list(CaseFilter filter, long afterId, int limit);

    Optional<CaseDetail> find(long caseId);

    /** Every field is optional; a null field does not filter. */
    record CaseFilter(CaseStatus status, CaseTeam team, CasePriority priority, CaseKind kind, String owner) {

        public static CaseFilter none() {
            return new CaseFilter(null, null, null, null, null);
        }
    }

    /** {@code nextAfterId} is the id to pass as {@code afterId} for the next page, or null on the last page. */
    record CasePage(List<CaseRecord> items, Long nextAfterId) {

        public CasePage {
            items = List.copyOf(items);
        }
    }

    record Activity(int ordinal, CaseActivityType type, String actor, CaseStatus from, CaseStatus to, String detail,
            Instant at) {
    }

    record Comment(int ordinal, String author, String body, Instant at) {
    }

    record Assignment(int ordinal, String owner, CaseTeam team, String assignedBy, String reason, Instant at) {
    }

    /** One row of the claim ledger that names this case. */
    record Claim(long seq, String action, String source, Long statementSeq, Long ledgerSeq, String actor) {
    }

    record CaseDetail(CaseRecord record, String origin, Long originRunId, Integer originOrdinal, Long linkedCaseId,
            String resolutionType, String resolutionReason, Instant resolvedAt, Instant createdAt, Instant updatedAt,
            List<Activity> activity, List<Comment> comments, List<Assignment> assignments, List<Claim> claims) {

        public CaseDetail {
            activity = List.copyOf(activity);
            comments = List.copyOf(comments);
            assignments = List.copyOf(assignments);
            claims = List.copyOf(claims);
        }
    }
}