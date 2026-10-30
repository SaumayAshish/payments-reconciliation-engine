package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.CaseReader;
import com.saumay.payments.application.CaseRecord;
import com.saumay.payments.domain.cases.CaseActivityType;
import com.saumay.payments.domain.cases.CaseClass;
import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CasePriority;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.CaseTeam;
import com.saumay.payments.domain.money.CurrencyCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL reader for the case queue and case view. Filters are fixed predicates with bound parameters, so nothing
 * a client sends reaches the SQL text. The detail runs in one read-only REPEATABLE READ transaction: every part of it
 * comes from the same snapshot.
 */
public final class JdbcCaseReader implements CaseReader {

    private static final String COLUMNS = "id, kind, case_class, amount_minor, currency, status, priority, team,"
            + " routing_rule, owner, created_business_date, sla_business_days, due_business_date, version";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;

    public JdbcCaseReader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public CasePage list(CaseFilter filter, long afterId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM exception_case WHERE id > ?");
        List<Object> args = new ArrayList<>();
        args.add(afterId);
        restrict(sql, args, "status", filter.status() == null ? null : filter.status().name());
        restrict(sql, args, "team", filter.team() == null ? null : filter.team().name());
        restrict(sql, args, "priority", filter.priority() == null ? null : filter.priority().name());
        restrict(sql, args, "kind", filter.kind() == null ? null : filter.kind().name());
        restrict(sql, args, "owner", filter.owner());
        sql.append(" ORDER BY id LIMIT ?");
        args.add(limit + 1);

        List<CaseRecord> rows = jdbc.query(sql.toString(), JdbcCaseReader::mapCase, args.toArray());
        if (rows.size() > limit) {
            List<CaseRecord> page = rows.subList(0, limit);
            return new CasePage(page, page.get(limit - 1).id());
        }
        return new CasePage(rows, null);
    }

    @Override
    public Optional<CaseDetail> find(long caseId) {
        return Objects.requireNonNull(snapshot.execute(status -> read(caseId)));
    }

    private Optional<CaseDetail> read(long caseId) {
        List<Header> headers = jdbc.query("SELECT " + COLUMNS + ", origin, origin_run_id, origin_ordinal,"
                + " linked_case_id, resolution_type, resolution_reason, resolved_at, created_at, updated_at"
                + " FROM exception_case WHERE id = ?", JdbcCaseReader::mapHeader, caseId);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        Header h = headers.get(0);
        List<Activity> activity = jdbc.query("SELECT ordinal, activity_type, actor, from_status, to_status, detail,"
                + " occurred_at FROM case_activity WHERE case_id = ? ORDER BY ordinal",
                (rs, row) -> new Activity(rs.getInt("ordinal"),
                        CaseActivityType.valueOf(rs.getString("activity_type")), rs.getString("actor"),
                        status(rs.getString("from_status")), status(rs.getString("to_status")),
                        rs.getString("detail"), instant(rs, "occurred_at")), caseId);
        List<Comment> comments = jdbc.query("SELECT ordinal, author, body, created_at FROM case_comment"
                + " WHERE case_id = ? ORDER BY ordinal",
                (rs, row) -> new Comment(rs.getInt("ordinal"), rs.getString("author"), rs.getString("body"),
                        instant(rs, "created_at")), caseId);
        List<Assignment> assignments = jdbc.query("SELECT ordinal, owner, team, assigned_by, reason, assigned_at"
                + " FROM case_assignment WHERE case_id = ? ORDER BY ordinal",
                (rs, row) -> new Assignment(rs.getInt("ordinal"), rs.getString("owner"),
                        CaseTeam.valueOf(rs.getString("team")), rs.getString("assigned_by"), rs.getString("reason"),
                        instant(rs, "assigned_at")), caseId);
        List<Claim> claims = jdbc.query("SELECT seq, action, source, statement_seq, ledger_seq, actor FROM line_claim"
                + " WHERE case_id = ? ORDER BY seq",
                (rs, row) -> new Claim(rs.getLong("seq"), rs.getString("action"), rs.getString("source"),
                        rs.getObject("statement_seq", Long.class), rs.getObject("ledger_seq", Long.class),
                        rs.getString("actor")), caseId);
        return Optional.of(new CaseDetail(h.record(), h.origin(), h.originRunId(), h.originOrdinal(),
                h.linkedCaseId(), h.resolutionType(), h.resolutionReason(), h.resolvedAt(), h.createdAt(),
                h.updatedAt(), activity, comments, assignments, claims));
    }

    private static void restrict(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private record Header(CaseRecord record, String origin, Long originRunId, Integer originOrdinal,
            Long linkedCaseId, String resolutionType, String resolutionReason, Instant resolvedAt,
            Instant createdAt, Instant updatedAt) {
    }

    private static Header mapHeader(ResultSet rs, int row) throws SQLException {
        return new Header(mapCase(rs, row), rs.getString("origin"), rs.getObject("origin_run_id", Long.class),
                rs.getObject("origin_ordinal", Integer.class), rs.getObject("linked_case_id", Long.class),
                rs.getString("resolution_type"), rs.getString("resolution_reason"), instant(rs, "resolved_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static CaseRecord mapCase(ResultSet rs, int row) throws SQLException {
        return new CaseRecord(rs.getLong("id"), CaseKind.valueOf(rs.getString("kind")),
                CaseClass.valueOf(rs.getString("case_class")), rs.getLong("amount_minor"),
                CurrencyCode.of(rs.getString("currency")), CaseStatus.valueOf(rs.getString("status")),
                CasePriority.valueOf(rs.getString("priority")), CaseTeam.valueOf(rs.getString("team")),
                rs.getString("routing_rule"), rs.getString("owner"),
                rs.getObject("created_business_date", LocalDate.class), rs.getInt("sla_business_days"),
                rs.getObject("due_business_date", LocalDate.class), rs.getLong("version"));
    }

    private static CaseStatus status(String name) {
        return name == null ? null : CaseStatus.valueOf(name);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}