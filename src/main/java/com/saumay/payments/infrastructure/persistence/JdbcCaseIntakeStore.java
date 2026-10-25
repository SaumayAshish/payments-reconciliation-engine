package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.CaseIntakeStore;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL adapter for {@link CaseIntakeStore}. The partial unique index on {@code natural_key} (origin RUN) is
 * what makes intake idempotent: {@code ON CONFLICT DO NOTHING} skips a case that already exists, even when two
 * intakes race, and only a case that was really inserted gets its CREATED activity row.
 */
public final class JdbcCaseIntakeStore implements CaseIntakeStore {

    static final String SYSTEM_ACTOR = "system";

    private static final String SELECT_RUN = "SELECT status, business_date FROM reconciliation_run WHERE id = ?";
    private static final String SELECT_ITEMS =
            "SELECT x.ordinal, x.kind, x.natural_key,"
                    + " COALESCE(s.amount_minor, e.amount_minor) AS amount_minor,"
                    + " COALESCE(s.currency, e.currency) AS currency"
                    + " FROM run_exception x"
                    + " JOIN run_exception_line l ON l.run_id = x.run_id AND l.exception_ordinal = x.ordinal"
                    + "  AND l.role = 'SUBJECT' AND l.position = 1"
                    + " LEFT JOIN statement_line s ON s.seq = l.statement_seq"
                    + " LEFT JOIN ledger_entry e ON e.seq = l.ledger_seq"
                    + " WHERE x.run_id = ? ORDER BY x.ordinal";
    private static final String INSERT_CASE =
            "INSERT INTO exception_case (origin, origin_run_id, origin_ordinal, natural_key, kind, case_class,"
                    + " amount_minor, currency, priority, team, routing_rule, created_business_date,"
                    + " sla_business_days, due_business_date)"
                    + " VALUES ('RUN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (natural_key) WHERE origin = 'RUN' DO NOTHING RETURNING id";
    private static final String INSERT_ACTIVITY =
            "INSERT INTO case_activity (case_id, ordinal, activity_type, actor, detail, occurred_at)"
                    + " VALUES (?, 1, 'CREATED', ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public JdbcCaseIntakeStore(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<IntakeRun> loadIntake(long runId) {
        List<RunRow> runs = jdbc.query(SELECT_RUN,
                (rs, n) -> new RunRow(rs.getString("status"), rs.getObject("business_date", LocalDate.class)), runId);
        if (runs.isEmpty()) {
            return Optional.empty();
        }
        List<IntakeItem> items = jdbc.query(SELECT_ITEMS,
                (rs, n) -> new IntakeItem(rs.getInt("ordinal"), rs.getString("kind"), rs.getString("natural_key"),
                        rs.getLong("amount_minor"), rs.getString("currency").trim()), runId);
        RunRow run = runs.get(0);
        return Optional.of(new IntakeRun(runId, run.status(), run.businessDate(), items));
    }

    @Override
    public int insert(List<NewCase> cases) {
        return Objects.requireNonNull(transaction.execute(status -> insertInTransaction(cases)));
    }

    private int insertInTransaction(List<NewCase> cases) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<Object[]> activities = new ArrayList<>();
        for (NewCase c : cases) {
            List<Long> ids = jdbc.queryForList(INSERT_CASE, Long.class,
                    c.runId(), c.ordinal(), c.naturalKey(), c.kind().name(), c.caseClass().name(),
                    c.amountMinor(), c.currency().code(), c.routing().priority().name(), c.routing().team().name(),
                    c.routing().ruleName(), c.createdBusinessDate(), c.slaBusinessDays(), c.dueBusinessDate());
            if (!ids.isEmpty()) {
                String detail = "Opened from run " + c.runId() + " exception " + c.ordinal()
                        + " by rule " + c.routing().ruleName();
                activities.add(new Object[] {ids.get(0), SYSTEM_ACTOR, detail, now});
            }
        }
        if (!activities.isEmpty()) {
            jdbc.batchUpdate(INSERT_ACTIVITY, activities);
        }
        return activities.size();
    }

    private record RunRow(String status, LocalDate businessDate) {
    }
}