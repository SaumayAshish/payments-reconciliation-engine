package com.saumay.payments.application;

import com.saumay.payments.domain.cases.CasePriority;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.CaseTeam;
import com.saumay.payments.domain.cases.MatchLine;
import com.saumay.payments.domain.cases.MatchLine.Side;
import com.saumay.payments.domain.cases.ResolutionType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Persistence port for resolving a case with a manual match or split and for unmatching (ADR-0009). Resolving
 * and unmatching each write the case, its timeline and the claim rows in one transaction, or not at all.
 */
public interface CaseMatchStore {

    Optional<CaseState> findState(long caseId);

    /** The lines that exist among the given sequence numbers, ordered by sequence within each side. */
    List<MatchLine> loadLines(List<Long> statementSeqs, List<Long> ledgerSeqs);

    /** The lines a run-origin case is about; empty for an unmatch case. */
    List<SubjectLine> subjectLines(long caseId);

    ResolveOutcome resolve(Resolution resolution, Instant at);

    /** The claims on a resolved case's lines that nobody has released yet. */
    Optional<ActiveMatch> findActiveMatchOfCase(long caseId);

    /** The unreleased claims of an auto-confirmed run match. */
    Optional<ActiveMatch> findActiveMatchOfRunMatch(long runId, int matchOrdinal);

    /** Releases the claims and opens the new case; empty when someone already released them. */
    OptionalLong unmatch(ActiveMatch match, NewUnmatchCase newCase, String actor, String reason, Instant at);

    enum ResolveOutcome {
        RESOLVED,
        VERSION_CONFLICT,
        LINE_ALREADY_CLAIMED
    }

    record CaseState(CaseStatus status, long version) {
        public CaseState {
            Objects.requireNonNull(status, "status");
        }
    }

    record SubjectLine(Side side, long seq) {
        public SubjectLine {
            Objects.requireNonNull(side, "side");
        }
    }

    record Resolution(long caseId, long expectedVersion, ResolutionType type, String actor, String reason,
            List<MatchLine> lines) {
        public Resolution {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(reason, "reason");
            lines = List.copyOf(lines);
        }
    }

    record ClaimedLine(long claimSeq, MatchLine line) {
        public ClaimedLine {
            Objects.requireNonNull(line, "line");
        }
    }

    /** The claims to release; linkedCaseId is the resolved case they belong to, or null for a run match. */
    record ActiveMatch(List<ClaimedLine> lines, Long linkedCaseId) {
        public ActiveMatch {
            lines = List.copyOf(lines);
        }
    }

    record NewUnmatchCase(String naturalKey, long amountMinor, String currency, CasePriority priority, CaseTeam team,
            String routingRule, LocalDate createdBusinessDate, int slaBusinessDays, LocalDate dueBusinessDate) {
        public NewUnmatchCase {
            Objects.requireNonNull(naturalKey, "naturalKey");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(priority, "priority");
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(routingRule, "routingRule");
            Objects.requireNonNull(createdBusinessDate, "createdBusinessDate");
            Objects.requireNonNull(dueBusinessDate, "dueBusinessDate");
        }
    }
}