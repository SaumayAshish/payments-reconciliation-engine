package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.CaseMatchStore;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.MatchLine;
import com.saumay.payments.domain.cases.MatchLine.Side;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL adapter for {@link CaseMatchStore}. Resolving updates the case row first (the version check is the
 * optimistic lock), then writes the claims; the claim trigger refuses a line that is already actively claimed, and
 * because that refusal aborts the whole transaction a failed resolution leaves no trace (ADR-0009). Every claim
 * writer holds the shared run cut-off lock until commit so a run never reads a half-written claim set.
 */
public final class JdbcCaseMatchStore implements CaseMatchStore {

    private static final String SELECT_STATE = "SELECT status, version FROM exception_case WHERE id = ?";
    private static final String SELECT_STATEMENTS =
            "SELECT seq, amount_minor, currency FROM statement_line WHERE seq = ANY (?) ORDER BY seq";
    private static final String SELECT_LEDGER =
            "SELECT seq, amount_minor, currency FROM ledger_entry WHERE seq = ANY (?) ORDER BY seq";
    private static final String SELECT_SUBJECT =
            "SELECT l.statement_seq, l.ledger_seq FROM exception_case c"
                    + " JOIN run_exception_line l ON l.run_id = c.origin_run_id"
                    + "  AND l.exception_ordinal = c.origin_ordinal AND l.role = 'SUBJECT'"
                    + " WHERE c.id = ? ORDER BY l.position";
    private static final String RESOLVE_CASE =
            "UPDATE exception_case SET status = 'RESOLVED', resolution_type = ?, resolution_reason = ?,"
                    + " resolved_at = ?, updated_at = ?, version = version + 1"
                    + " WHERE id = ? AND version = ? AND status = 'INVESTIGATING'";
    private static final String INSERT_CLAIM =
            "INSERT INTO line_claim (action, statement_seq, ledger_seq, source, case_id, actor)"
                    + " VALUES ('CLAIM', CAST(? AS bigint), CAST(? AS bigint), ?, ?, ?)";
    private static final String INSERT_RELEASE =
            "INSERT INTO line_claim (action, statement_seq, ledger_seq, source, case_id, actor, releases_seq)"
                    + " VALUES ('RELEASE', CAST(? AS bigint), CAST(? AS bigint), 'UNMATCH', ?, ?, ?)";
    private static final String ACTIVE_CLAIMS =
            "SELECT c.seq, c.statement_seq, c.ledger_seq,"
                    + " COALESCE(s.amount_minor, e.amount_minor) AS amount_minor,"
                    + " COALESCE(s.currency, e.currency) AS currency"
                    + " FROM line_claim c"
                    + " LEFT JOIN statement_line s ON s.seq = c.statement_seq"
                    + " LEFT JOIN ledger_entry e ON e.seq = c.ledger_seq"
                    + " WHERE c.action = 'CLAIM' AND %s"
                    + " AND NOT EXISTS (SELECT 1 FROM line_claim r WHERE r.releases_seq = c.seq)"
                    + " ORDER BY c.seq";
    private static final String INSERT_UNMATCH_CASE =
            "INSERT INTO exception_case (origin, linked_case_id, natural_key, kind, case_class, amount_minor,"
                    + " currency, priority, team, routing_rule, created_business_date, sla_business_days,"
                    + " due_business_date)"
                    + " VALUES ('UNMATCH', CAST(? AS bigint), ?, 'LOW_CONFIDENCE', 'REVIEW', ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " RETURNING id";
    private static final String LOCK_CASE = "SELECT id FROM exception_case WHERE id = ? FOR UPDATE";
    private static final String NEXT_ORDINAL =
            "SELECT COALESCE(MAX(ordinal), 0) + 1 FROM case_activity WHERE case_id = ?";
    private static final String INSERT_RESOLVED_ACTIVITY =
            "INSERT INTO case_activity (case_id, ordinal, activity_type, actor, from_status, to_status, detail,"
                    + " occurred_at) VALUES (?, ?, 'RESOLVED', ?, 'INVESTIGATING', 'RESOLVED', ?, ?)";
    private static final String INSERT_CREATED_ACTIVITY =
            "INSERT INTO case_activity (case_id, ordinal, activity_type, actor, detail, occurred_at)"
                    + " VALUES (?, 1, 'CREATED', ?, ?, ?)";
    private static final String INSERT_REJECTED_ACTIVITY =
            "INSERT INTO case_activity (case_id, ordinal, activity_type, actor, detail, occurred_at)"
                    + " VALUES (?, ?, 'MATCH_REJECTED', ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcCaseMatchStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public Optional<CaseState> findState(long caseId) {
        return jdbc.query(SELECT_STATE,
                (rs, n) -> new CaseState(CaseStatus.valueOf(rs.getString("status")), rs.getLong("version")), caseId)
                .stream().findFirst();
    }

    @Override
    public List<MatchLine> loadLines(List<Long> statementSeqs, List<Long> ledgerSeqs) {
        List<MatchLine> lines = new ArrayList<>(load(SELECT_STATEMENTS, Side.STATEMENT, statementSeqs));
        lines.addAll(load(SELECT_LEDGER, Side.LEDGER, ledgerSeqs));
        return lines;
    }

    private List<MatchLine> load(String sql, Side side, List<Long> seqs) {
        if (seqs.isEmpty()) {
            return List.of();
        }
        return jdbc.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setArray(1, connection.createArrayOf("bigint", seqs.toArray(Long[]::new)));
            return ps;
        }, (rs, n) -> new MatchLine(side, rs.getLong("seq"), rs.getLong("amount_minor"),
                rs.getString("currency").trim()));
    }

    @Override
    public List<SubjectLine> subjectLines(long caseId) {
        return jdbc.query(SELECT_SUBJECT, (rs, n) -> {
            long statementSeq = rs.getLong("statement_seq");
            return rs.wasNull()
                    ? new SubjectLine(Side.LEDGER, rs.getLong("ledger_seq"))
                    : new SubjectLine(Side.STATEMENT, statementSeq);
        }, caseId);
    }

    @Override
    public ResolveOutcome resolve(Resolution resolution, Instant at) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> resolveInTransaction(resolution, at)));
        } catch (DuplicateKeyException e) {
            return ResolveOutcome.LINE_ALREADY_CLAIMED;
        }
    }

    private ResolveOutcome resolveInTransaction(Resolution r, Instant at) {
        OffsetDateTime now = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        int updated = jdbc.update(RESOLVE_CASE, r.type().name(), r.reason(), now, now, r.caseId(), r.expectedVersion());
        if (updated == 0) {
            return ResolveOutcome.VERSION_CONFLICT;
        }
        AdvisoryLocks.shared(jdbc, AdvisoryLocks.RUN_CUTOFF);
        StringBuilder described = new StringBuilder();
        for (MatchLine line : r.lines()) {
            jdbc.update(INSERT_CLAIM, line.side() == Side.STATEMENT ? line.seq() : null,
                    line.side() == Side.LEDGER ? line.seq() : null, r.type().name(), r.caseId(), r.actor());
            if (described.length() > 0) {
                described.append(", ");
            }
            described.append(line.side() == Side.STATEMENT ? "statement " : "ledger ").append(line.seq());
        }
        jdbc.update(INSERT_RESOLVED_ACTIVITY, r.caseId(), nextOrdinal(r.caseId()), r.actor(),
                r.type() + " of " + described + ": " + r.reason(), now);
        return ResolveOutcome.RESOLVED;
    }

    @Override
    public Optional<ActiveMatch> findActiveMatchOfCase(long caseId) {
        return active("c.case_id = ?", caseId, caseId);
    }

    @Override
    public Optional<ActiveMatch> findActiveMatchOfRunMatch(long runId, int matchOrdinal) {
        return active("c.run_id = ? AND c.run_match_ordinal = ?", null, runId, matchOrdinal);
    }

    private Optional<ActiveMatch> active(String predicate, Long linkedCaseId, Object... args) {
        List<ClaimedLine> lines = jdbc.query(String.format(ACTIVE_CLAIMS, predicate), (rs, n) -> {
            long statementSeq = rs.getLong("statement_seq");
            boolean statement = !rs.wasNull();
            long amount = rs.getLong("amount_minor");
            String currency = rs.getString("currency").trim();
            MatchLine line = statement
                    ? new MatchLine(Side.STATEMENT, statementSeq, amount, currency)
                    : new MatchLine(Side.LEDGER, rs.getLong("ledger_seq"), amount, currency);
            return new ClaimedLine(rs.getLong("seq"), line);
        }, args);
        return lines.isEmpty() ? Optional.empty() : Optional.of(new ActiveMatch(lines, linkedCaseId));
    }

    @Override
    public OptionalLong unmatch(ActiveMatch match, NewUnmatchCase newCase, String actor, String reason, Instant at) {
        try {
            return Objects.requireNonNull(
                    transaction.execute(status -> unmatchInTransaction(match, newCase, actor, reason, at)));
        } catch (DuplicateKeyException e) {
            return OptionalLong.empty();
        }
    }

    private OptionalLong unmatchInTransaction(ActiveMatch match, NewUnmatchCase nc, String actor, String reason,
            Instant at) {
        OffsetDateTime now = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        AdvisoryLocks.shared(jdbc, AdvisoryLocks.RUN_CUTOFF);
        long newCaseId = Objects.requireNonNull(jdbc.queryForObject(INSERT_UNMATCH_CASE, Long.class,
                match.linkedCaseId(), nc.naturalKey(), nc.amountMinor(), nc.currency(), nc.priority().name(),
                nc.team().name(), nc.routingRule(), nc.createdBusinessDate(), nc.slaBusinessDays(),
                nc.dueBusinessDate()));
        String origin = match.linkedCaseId() != null ? "case " + match.linkedCaseId() : "an auto-confirmed run match";
        jdbc.update(INSERT_CREATED_ACTIVITY, newCaseId, actor, "Opened by unmatching " + origin + ": " + reason, now);
        for (ClaimedLine claimed : match.lines()) {
            MatchLine line = claimed.line();
            jdbc.update(INSERT_RELEASE, line.side() == Side.STATEMENT ? line.seq() : null,
                    line.side() == Side.LEDGER ? line.seq() : null, newCaseId, actor, claimed.claimSeq());
        }
        if (match.linkedCaseId() != null) {
            jdbc.queryForObject(LOCK_CASE, Long.class, match.linkedCaseId());
            jdbc.update(INSERT_REJECTED_ACTIVITY, match.linkedCaseId(), nextOrdinal(match.linkedCaseId()), actor,
                    "Match reversed and reopened as case " + newCaseId + ": " + reason, now);
        }
        return OptionalLong.of(newCaseId);
    }

    private int nextOrdinal(long caseId) {
        return Objects.requireNonNull(jdbc.queryForObject(NEXT_ORDINAL, Integer.class, caseId));
    }
}