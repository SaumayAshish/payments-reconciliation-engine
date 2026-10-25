package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saumay.payments.application.CaseErrorCode;
import com.saumay.payments.application.CaseRejectedException;
import com.saumay.payments.application.CaseService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * FRD-07 case actions against a real PostgreSQL 17: the whole lifecycle with its timeline and owner history, the
 * version rule, and two people acting on the same version at once.
 */
@Testcontainers
class JdbcCaseStoreIT {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    private static final String INSERT_RUN =
            "INSERT INTO reconciliation_run (business_date, status, ledger_cutoff_seq, auto_threshold_milli,"
                    + " review_threshold_milli, date_window_business_days, lookback_business_days,"
                    + " max_one_to_many_candidates, fuzzy_min_similarity_milli, fuzzy_counterparty_weight_milli)"
                    + " VALUES (DATE '2026-10-09', 'CREATED', 0, 950, 700, 3, 10, 8, 600, 600) RETURNING id";
    private static final String INSERT_EXCEPTION =
            "INSERT INTO run_exception (run_id, ordinal, kind, natural_key, reason, detail)"
                    + " VALUES (?, ?, 'MISSING_LEDGER', ?, 'r', 'd')";
    private static final String CASE_COLUMNS =
            "INSERT INTO exception_case (origin, origin_run_id, origin_ordinal, natural_key, kind, case_class,"
                    + " amount_minor, currency, priority, team, routing_rule, created_business_date,"
                    + " sla_business_days, due_business_date";
    private static final String CASE_VALUES =
            "'RUN', ?, ?, ?, 'MISSING_LEDGER', 'BREAK', 3000, 'GBP', 'P3', 'LEDGER_OPERATIONS', 'missing-ledger',"
                    + " DATE '2026-10-09', 5, DATE '2026-10-16'";
    private static final String INSERT_OPEN_CASE =
            CASE_COLUMNS + ") VALUES (" + CASE_VALUES + ") RETURNING id";
    private static final String INSERT_RESOLVED_CASE =
            CASE_COLUMNS + ", status, resolution_type, resolution_reason, resolved_at) VALUES (" + CASE_VALUES
                    + ", 'RESOLVED', 'MANUAL_MATCH', 'seeded', now()) RETURNING id";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static JdbcTemplate jdbc;
    private static CaseService service;
    private long runId;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        service = new CaseService(new JdbcCaseStore(dataSource), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void resetAndSeedARun() {
        jdbc.execute("TRUNCATE case_attachment, case_comment, case_assignment, case_activity, exception_case,"
                + " run_note, run_carried_forward, run_exception_line, run_exception, run_match_line,"
                + " run_match, reconciliation_run_statement_batch, reconciliation_run, rejected_line,"
                + " statement_line, ledger_entry, ingestion_file RESTART IDENTITY CASCADE");
        runId = jdbc.queryForObject(INSERT_RUN, Long.class);
    }

    @Test
    void aCaseCanBeWorkedThroughItsWholeLifecycleWithAFullTimeline() {
        long id = seedCase(1, false);

        service.assign(id, 0, "alice", "lead", "knows the ledger");
        service.startInvestigation(id, 1, "alice");
        service.escalate(id, 2, "alice", "needs authority", false);
        service.assign(id, 3, "bob", "lead", null);

        CaseState state = state(id);
        assertEquals("ASSIGNED", state.status());
        assertEquals("bob", state.owner());
        assertEquals("TEAM_LEAD", state.team());
        assertEquals("P2", state.priority());
        assertEquals("manual-escalation", state.routingRule());
        assertEquals(4L, state.version());
        assertEquals(List.of("ASSIGNED", "STATUS_CHANGED", "ESCALATED", "ASSIGNED"),
                strings("SELECT activity_type FROM case_activity WHERE case_id = ? ORDER BY ordinal", id));
        assertEquals(List.of("alice", "bob"),
                strings("SELECT owner FROM case_assignment WHERE case_id = ? ORDER BY ordinal", id));
        assertEquals(List.of("LEDGER_OPERATIONS", "TEAM_LEAD"),
                strings("SELECT team FROM case_assignment WHERE case_id = ? ORDER BY ordinal", id));
    }

    @Test
    void aStaleVersionIsRefusedAndWritesNothing() {
        long id = seedCase(1, false);
        service.assign(id, 0, "alice", "lead", null);

        CaseRejectedException e = assertThrows(CaseRejectedException.class,
                () -> service.assign(id, 0, "bob", "lead", null));

        assertEquals(CaseErrorCode.CASE_VERSION_CONFLICT, e.code());
        assertEquals("alice", state(id).owner());
        assertEquals(1L, state(id).version());
        assertEquals(1, strings("SELECT owner FROM case_assignment WHERE case_id = ?", id).size());
    }

    @Test
    void whenTwoPeopleActOnTheSameVersionExactlyOneWins() throws Exception {
        long id = seedCase(1, false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (String who : List.of("alice", "bob")) {
            Callable<String> attempt = () -> {
                start.await();
                try {
                    service.assign(id, 0, who, "lead", null);
                    return "OK";
                } catch (CaseRejectedException e) {
                    return e.code().name();
                }
            };
            results.add(pool.submit(attempt));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        Collections.sort(outcomes);

        assertEquals(List.of("CASE_VERSION_CONFLICT", "OK"), outcomes);
        assertEquals(1L, state(id).version());
        assertEquals(1, strings("SELECT owner FROM case_assignment WHERE case_id = ?", id).size());
        assertEquals(1, strings("SELECT activity_type FROM case_activity WHERE case_id = ?", id).size());
    }

    @Test
    void escalatingTwiceWritesOneEscalationOnly() {
        long id = seedCase(1, false);
        service.escalate(id, 0, "lead", "late", false);
        service.escalate(id, 0, "lead", "late", false);

        assertEquals(1, strings("SELECT activity_type FROM case_activity WHERE case_id = ?"
                + " AND activity_type = 'ESCALATED'", id).size());
        assertEquals(1L, state(id).version());
    }

    @Test
    void commentsGetSequentialOrdinalsAndAppearOnTheTimeline() {
        long id = seedCase(1, false);

        assertEquals(1, service.comment(id, "alice", "first"));
        service.assign(id, 0, "alice", "lead", null);
        assertEquals(2, service.comment(id, "alice", "second"));

        assertEquals(List.of("first", "second"),
                strings("SELECT body FROM case_comment WHERE case_id = ? ORDER BY ordinal", id));
        assertEquals(List.of("COMMENT_ADDED", "ASSIGNED", "COMMENT_ADDED"),
                strings("SELECT activity_type FROM case_activity WHERE case_id = ? ORDER BY ordinal", id));
        assertEquals(1L, state(id).version());
    }

    @Test
    void aResolvedCaseRefusesActionsButStillTakesComments() {
        long id = seedCase(1, true);

        assertEquals(CaseErrorCode.ILLEGAL_STATE_TRANSITION, assertThrows(CaseRejectedException.class,
                () -> service.assign(id, 0, "alice", "lead", null)).code());
        assertEquals(CaseErrorCode.ILLEGAL_STATE_TRANSITION, assertThrows(CaseRejectedException.class,
                () -> service.escalate(id, 0, "lead", null, false)).code());
        assertEquals(1, service.comment(id, "auditor", "reviewed"));

        assertEquals("RESOLVED", state(id).status());
        assertEquals(0L, state(id).version());
    }

    private long seedCase(int ordinal, boolean resolved) {
        jdbc.update(INSERT_EXCEPTION, runId, ordinal, "MISSING_LEDGER:nk-" + ordinal);
        return jdbc.queryForObject(resolved ? INSERT_RESOLVED_CASE : INSERT_OPEN_CASE, Long.class,
                runId, ordinal, "MISSING_LEDGER:nk-" + ordinal);
    }

    private static CaseState state(long id) {
        return jdbc.queryForObject("SELECT status, owner, team, priority, routing_rule, version"
                        + " FROM exception_case WHERE id = ?",
                (rs, n) -> new CaseState(rs.getString("status"), rs.getString("owner"), rs.getString("team"),
                        rs.getString("priority"), rs.getString("routing_rule"), rs.getLong("version")), id);
    }

    private static List<String> strings(String sql, long id) {
        return jdbc.queryForList(sql, String.class, id);
    }

    private record CaseState(String status, String owner, String team, String priority, String routingRule,
            long version) {
    }
}