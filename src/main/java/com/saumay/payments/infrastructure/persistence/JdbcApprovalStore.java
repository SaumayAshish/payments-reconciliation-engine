package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.ApprovalStore;
import com.saumay.payments.application.AuditLog;
import com.saumay.payments.application.AuditNote;
import com.saumay.payments.application.CaseMatchStore;
import com.saumay.payments.domain.approval.ApprovalRequest;
import com.saumay.payments.domain.approval.ApprovalStatus;
import com.saumay.payments.domain.approval.ControlledAction;
import com.saumay.payments.domain.audit.AuditDraft;
import com.saumay.payments.domain.audit.AuditTime;
import com.saumay.payments.domain.cases.CaseStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL adapter for {@link ApprovalStore}. Every write locks the case row first, then the approval row, so two
 * deciders and a case action always queue in the same order. The audit events are the last statements of each
 * transaction (ADR-0010). Approving reuses the manual-match resolve transaction, so a controlled resolution claims
 * lines exactly the way a manual match does and the claim ledger still refuses a line that is already matched.
 */
public final class JdbcApprovalStore implements ApprovalStore {

    private static final String COLUMNS = "id, case_id, action, amount_minor, currency, reason, maker, requested_at,"
            + " config_version, status, checker, decided_at, decision_note";
    private static final String SELECT_CASE =
            "SELECT status, version, amount_minor, currency FROM exception_case WHERE id = ?";
    private static final String LOCK_CASE = SELECT_CASE + " FOR UPDATE";
    private static final String SELECT_APPROVAL = "SELECT " + COLUMNS + " FROM approval_request WHERE id = ?";
    private static final String SELECT_PENDING =
            "SELECT " + COLUMNS + " FROM approval_request WHERE case_id = ? AND status = 'PENDING'";
    private static final String INSERT_APPROVAL =
            "INSERT INTO approval_request (case_id, action, amount_minor, currency, reason, maker, requested_at,"
                    + " config_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
    private static final String DECIDE =
            "UPDATE approval_request SET status = ?, checker = ?, decided_at = ?, decision_note = ?"
                    + " WHERE id = ? AND status = 'PENDING'";
    private static final String NEXT_ORDINAL =
            "SELECT COALESCE(MAX(ordinal), 0) + 1 FROM case_activity WHERE case_id = ?";
    private static final String INSERT_ACTIVITY =
            "INSERT INTO case_activity (case_id, ordinal, activity_type, actor, detail, occurred_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?)";

    private static final RowMapper<CaseFacts> FACTS = (rs, n) -> new CaseFacts(
            CaseStatus.valueOf(rs.getString("status")), rs.getLong("version"), rs.getLong("amount_minor"),
            rs.getString("currency").trim());

    private static final RowMapper<ApprovalRequest> REQUEST = (rs, n) -> new ApprovalRequest(
            rs.getLong("id"), rs.getLong("case_id"), ControlledAction.valueOf(rs.getString("action")),
            rs.getLong("amount_minor"), rs.getString("currency").trim(), rs.getString("reason"),
            rs.getString("maker"), rs.getObject("requested_at", OffsetDateTime.class).toInstant(),
            rs.getLong("config_version"), ApprovalStatus.valueOf(rs.getString("status")), rs.getString("checker"),
            instantOrNull(rs.getObject("decided_at", OffsetDateTime.class)), rs.getString("decision_note"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AuditLog audit;
    private final JdbcCaseMatchStore cases;

    public JdbcApprovalStore(DataSource dataSource, AuditLog audit) {
        this.audit = Objects.requireNonNull(audit, "audit");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.cases = new JdbcCaseMatchStore(dataSource, audit);
    }

    @Override
    public Optional<CaseFacts> findCase(long caseId) {
        return jdbc.query(SELECT_CASE, FACTS, caseId).stream().findFirst();
    }

    @Override
    public Optional<ApprovalRequest> find(long approvalId) {
        return jdbc.query(SELECT_APPROVAL, REQUEST, approvalId).stream().findFirst();
    }

    @Override
    public Optional<ApprovalRequest> findPendingForCase(long caseId) {
        return jdbc.query(SELECT_PENDING, REQUEST, caseId).stream().findFirst();
    }

    @Override
    public SubmitResult submit(Submission submission, Instant at) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> submitInTransaction(submission, at)));
        } catch (DuplicateKeyException e) {
            return new SubmitResult(SubmitOutcome.ALREADY_PENDING, 0);
        }
    }

    private SubmitResult submitInTransaction(Submission s, Instant at) {
        ApprovalRequest r = s.request();
        List<CaseFacts> locked = jdbc.query(LOCK_CASE, FACTS, r.caseId());
        if (locked.isEmpty() || locked.get(0).status() != CaseStatus.INVESTIGATING
                || locked.get(0).version() != s.expectedCaseVersion()) {
            return new SubmitResult(SubmitOutcome.VERSION_CONFLICT, 0);
        }
        OffsetDateTime when = OffsetDateTime.ofInstant(r.requestedAt(), ZoneOffset.UTC);
        long id = Objects.requireNonNull(jdbc.queryForObject(INSERT_APPROVAL, Long.class, r.caseId(),
                r.action().name(), r.amountMinor(), r.currency(), r.reason(), r.maker(), when, r.configVersion()));
        activity(r.caseId(), "APPROVAL_REQUESTED", r.maker(),
                r.action() + " of " + r.amountMinor() + " " + r.currency() + " needs approval: " + r.reason(), when);
        appendAudit(id, r.maker(), s.audit().apply(id), at);
        return new SubmitResult(SubmitOutcome.SUBMITTED, id);
    }

    @Override
    public DecisionOutcome approve(Approval approval, Instant at) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> approveInTransaction(approval, at, status)));
        } catch (DuplicateKeyException e) {
            return DecisionOutcome.LINE_ALREADY_CLAIMED;
        }
    }

    private DecisionOutcome approveInTransaction(Approval a, Instant at, TransactionStatus status) {
        ApprovalRequest d = a.decided();
        jdbc.query(LOCK_CASE, FACTS, d.caseId());
        if (!decide(d)) {
            return DecisionOutcome.ALREADY_DECIDED;
        }
        activity(d.caseId(), "APPROVAL_APPROVED", d.checker(), d.action() + " approved by " + d.checker(),
                OffsetDateTime.ofInstant(d.decidedAt(), ZoneOffset.UTC));
        if (cases.resolveInTransaction(a.resolution(), at) != CaseMatchStore.ResolveOutcome.RESOLVED) {
            status.setRollbackOnly();
            return DecisionOutcome.VERSION_CONFLICT;
        }
        appendAudit(d.id(), d.checker(), a.audit(), at);
        return DecisionOutcome.DECIDED;
    }

    @Override
    public DecisionOutcome reject(Rejection rejection, Instant at) {
        return Objects.requireNonNull(transaction.execute(status -> rejectInTransaction(rejection, at)));
    }

    private DecisionOutcome rejectInTransaction(Rejection rj, Instant at) {
        ApprovalRequest d = rj.decided();
        jdbc.query(LOCK_CASE, FACTS, d.caseId());
        if (!decide(d)) {
            return DecisionOutcome.ALREADY_DECIDED;
        }
        activity(d.caseId(), "APPROVAL_REJECTED", d.checker(),
                d.action() + " rejected by " + d.checker() + ": " + d.decisionNote(),
                OffsetDateTime.ofInstant(d.decidedAt(), ZoneOffset.UTC));
        appendAudit(d.id(), d.checker(), rj.audit(), at);
        return DecisionOutcome.DECIDED;
    }

    @Override
    public void recordDenied(DeniedAttempt attempt, Instant at) {
        transaction.executeWithoutResult(status -> appendAudit(attempt.approvalId(), attempt.actor(), attempt.audit(), at));
    }

    private boolean decide(ApprovalRequest d) {
        return jdbc.update(DECIDE, d.status().name(), d.checker(),
                OffsetDateTime.ofInstant(d.decidedAt(), ZoneOffset.UTC), d.decisionNote(), d.id()) == 1;
    }

    private void activity(long caseId, String type, String actor, String detail, OffsetDateTime when) {
        long ordinal = Objects.requireNonNull(jdbc.queryForObject(NEXT_ORDINAL, Long.class, caseId));
        jdbc.update(INSERT_ACTIVITY, caseId, ordinal, type, actor, detail, when);
    }

    private void appendAudit(long approvalId, String actor, AuditNote note, Instant at) {
        audit.append(new AuditDraft("approval_request", String.valueOf(approvalId), actor, note.action(),
                note.beforeState(), note.afterState(), AuditTime.micros(at)));
    }

    private static Instant instantOrNull(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}