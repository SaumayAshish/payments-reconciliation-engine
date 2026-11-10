package com.saumay.payments.application;

import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CaseTeam;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Reads a current snapshot of resolved cases for elapsed-time statistics. */
public interface ResolutionReportReader {

    List<ResolvedCase> resolvedCases();

    record ResolvedCase(CaseTeam team, CaseKind kind, Instant createdAt, Instant resolvedAt) {
        public ResolvedCase {
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(resolvedAt, "resolvedAt");
        }
    }
}