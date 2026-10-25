package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.CaseRecord;
import com.saumay.payments.application.CaseStore;
import com.saumay.payments.domain.cases.CaseClass;
import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CasePriority;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.CaseTeam;
import com.saumay.payments.domain.money.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL adapter for {@link CaseStore}. A change is {@code UPDATE ... WHERE id = ? AND version = ?}: if another
 * writer got there first the row no longer matches, nothing is written and the caller sees a conflict. The case row
 * stays locked until commit, so the timeline ordinals computed afterwards cannot collide.
 */
public final class JdbcCaseStore implements CaseStore {

    private static final String SELECT_CASE =
            "SELECT id, kind, case_class, amount_minor, currency, status, priority, team, routing_rule, owner,"
                    + " created_business_date, sla_business_days, due_business_date, version"
                    + " FROM exception_case WHERE id = ?";
    private static final String UPDATE_CASE =
            "UPDATE exception_case SET status = ?, owner = ?, team = ?, priority = ?, routing_rule = ?,"
                    + " version = version + 1, updated_at = ? WHERE id = ? AND version = ?";
    private static final String LOCK_CASE = "SELECT id FROM exception_case WHERE id = ? FOR UPDATE";
    private static final String NEXT_ACTIVITY =
            "SELECT COALESCE(MAX(ordinal), 0) + 1 FROM case_activity WHERE case_id = ?";
    private static final String NEXT_ASSIGNMENT =
            "SELECT COALESCE(MAX(ordinal), 0) + 1 FROM case_assignment WHERE case_id = ?";
    private static final String NEXT_COMMENT =
            "SELECT COALESCE(MAX(ordinal), 0) + 1 FROM case_comment WHERE case_id = ?";
    private static final String INSERT_ACTIVITY =
            "INSERT INTO case_activity (case_id, ordinal, activity_type, actor, from_status, to_status, detail,"
                    + " occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_ASSIGNMENT =
            "INSERT INTO case_assignment (case_id, ordinal, owner, team, assigned_by, reason, assigned_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_COMMENT =
            "INSERT INTO case_comment (case_id, ordinal, author, body, created_at) VALUES (?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcCaseStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public Optional<CaseRecord> find(long caseId) {
        List<CaseRecord> rows = jdbc.query(SELECT_CASE, (rs, n) -> new CaseRecord(rs.getLong("id"),
                CaseKind.valueOf(rs.getString("kind")), CaseClass.valueOf(rs.getString("case_class")),
                rs.getLong("amount_minor"), CurrencyCode.of(rs.getString("currency").trim()),
                CaseStatus.valueOf(rs.getString("status")), CasePriority.valueOf(rs.getString("priority")),
                CaseTeam.valueOf(rs.getString("team")), rs.getString("routing_rule"), rs.getString("owner"),
                rs.getObject("created_business_date", LocalDate.class), rs.getInt("sla_business_days"),
                rs.getObject("due_business_date", LocalDate.class), rs.getLong("version")), caseId);
        return rows.stream().findFirst();
    }

    @Override
    public boolean apply(CaseChange change, Instant at) {
        return Boolean.TRUE.equals(transaction.execute(status -> applyInTransaction(change, at)));
    }

    private boolean applyInTransaction(CaseChange change, Instant at) {
        OffsetDateTime when = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        int updated = jdbc.update(UPDATE_CASE, change.status().name(), change.owner(), change.team().name(),
                change.priority().name(), change.routingRule(), when, change.caseId(), change.expectedVersion());
        if (updated == 0) {
            return false;
        }
        Activity activity = change.activity();
        jdbc.update(INSERT_ACTIVITY, change.caseId(), next(NEXT_ACTIVITY, change.caseId()), activity.type().name(),
                activity.actor(), activity.from() == null ? null : activity.from().name(),
                activity.to() == null ? null : activity.to().name(), activity.detail(), when);
        Assignment assignment = change.assignment();
        if (assignment != null) {
            jdbc.update(INSERT_ASSIGNMENT, change.caseId(), next(NEXT_ASSIGNMENT, change.caseId()),
                    assignment.owner(), assignment.team().name(), assignment.assignedBy(), assignment.reason(), when);
        }
        return true;
    }

    @Override
    public int addComment(long caseId, String author, String body, Instant at) {
        return Objects.requireNonNull(transaction.execute(status -> addCommentInTransaction(caseId, author, body, at)));
    }

    private int addCommentInTransaction(long caseId, String author, String body, Instant at) {
        if (jdbc.queryForList(LOCK_CASE, Long.class, caseId).isEmpty()) {
            throw new IllegalStateException("Case " + caseId + " does not exist");
        }
        OffsetDateTime when = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        int commentOrdinal = next(NEXT_COMMENT, caseId);
        jdbc.update(INSERT_COMMENT, caseId, commentOrdinal, author, body, when);
        jdbc.update(INSERT_ACTIVITY, caseId, next(NEXT_ACTIVITY, caseId), "COMMENT_ADDED", author, null, null,
                "Comment " + commentOrdinal, when);
        return commentOrdinal;
    }

    private int next(String sql, long caseId) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, caseId));
    }
}