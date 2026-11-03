package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.application.AuditLog;
import com.saumay.payments.application.CaseRejectedException;
import com.saumay.payments.application.CaseService;
import com.saumay.payments.domain.audit.AuditChain;
import com.saumay.payments.domain.audit.AuditDraft;
import com.saumay.payments.domain.audit.AuditEvent;
import com.saumay.payments.domain.audit.AuditHead;
import com.saumay.payments.domain.audit.AuditTime;
import com.saumay.payments.domain.audit.AuditVerification;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
 * Every case action leaves exactly one audit event, written in the same transaction as the action (ADR-0010): the
 * event carries the state before and after, refused or no-op actions leave none, and if the audit log cannot be
 * written the action does not happen at all.
 */
@Testcontainers
class CaseActionAuditIT {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    private static final String INSERT_RUN =
            "INSERT INTO reconciliation_run (business_date, status, ledger_cutoff_seq, auto_threshold_milli,"
                    + " review_threshold_milli, date_window_business_days, lookback_business_days,"
                    + " max_one_to_many_candidates, fuzzy_min_similarity_milli, fuzzy_counterparty_weight_milli)"
                    + " VALUES (DATE '2026-10-09', 'CREATED', 0, 950, 700, 3, 10, 8, 600, 600) RETURNING id";
    private static final String INSERT_EXCEPTION =
            "INSERT INTO run_exception (run_id, ordinal, kind, natural_key, reason, detail)"
                    + " VALUES (?, ?, 'MISSING_LEDGER', ?, 'r', 'd')";
    private static final String INSERT_OPEN_CASE =
            "INSERT INTO exception_case (origin, origin_run_id, origin_ordinal, natural_key, kind, case_class,"
                    + " amount_minor, currency, priority, team, routing_rule, created_business_date,"
                    + " sla_business_days, due_business_date) VALUES ('RUN', ?, ?, ?, 'MISSING_LEDGER', 'BREAK',"
                    + " 3000, 'GBP', 'P3', 'LEDGER_OPERATIONS', 'missing-ledger', DATE '2026-10-09', 5,"
                    + " DATE '2026-10-16') RETURNING id";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static CaseService service;
    private long runId;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        service = new CaseService(new JdbcCaseStore(dataSource, new JdbcAuditLog(dataSource)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void resetAndSeedARun() {
        jdbc.execute("TRUNCATE case_attachment, case_comment, case_assignment, case_activity, exception_case,"
                + " run_note, run_carried_forward, run_exception_line, run_exception, run_match_line,"
                + " run_match, reconciliation_run_statement_batch, reconciliation_run, rejected_line,"
                + " statement_line, ledger_entry, ingestion_file RESTART IDENTITY CASCADE");
        jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER USER");
        jdbc.execute("TRUNCATE audit_event");
        jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER USER");
        runId = jdbc.queryForObject(INSERT_RUN, Long.class);
    }

    @Test
    void assigningACaseRecordsTheStateBeforeAndAfterWithTheActorAndTime() {
        long id = seedCase(1);

        service.assign(id, 0, "alice", "lead", "knows the ledger");

        List<Map<String, Object>> events = events(id);
        assertEquals(1, events.size());
        Map<String, Object> event = events.get(0);
        assertEquals("CASE_ASSIGNED", event.get("action"));
        assertEquals("lead", event.get("actor"));
        assertEquals(state(id, null, "OPEN", "LEDGER_OPERATIONS", "P3", "missing-ledger", 0),
                event.get("before_state"));
        assertEquals(state(id, "alice", "ASSIGNED", "LEDGER_OPERATIONS", "P3", "missing-ledger", 1),
                event.get("after_state"));
        assertEquals(AuditTime.micros(NOW), ((Number) event.get("occurred_at_micros")).longValue());
    }

    @Test
    void theWholeLifecycleLeavesOneEventPerActionInOrderAndTheChainVerifies() {
        long id = seedCase(1);

        service.assign(id, 0, "alice", "lead", null);
        service.startInvestigation(id, 1, "alice");
        service.escalate(id, 2, "alice", "needs authority", false);

        List<Map<String, Object>> events = events(id);
        assertEquals(List.of("CASE_ASSIGNED", "CASE_INVESTIGATION_STARTED", "CASE_ESCALATED"),
                events.stream().map(e -> (String) e.get("action")).toList());
        Map<String, Object> escalated = events.get(2);
        assertEquals(state(id, "alice", "INVESTIGATING", "LEDGER_OPERATIONS", "P3", "missing-ledger", 2),
                escalated.get("before_state"));
        assertEquals(state(id, "alice", "ESCALATED", "TEAM_LEAD", "P2", "manual-escalation", 3),
                escalated.get("after_state"));
        assertTrue(new JdbcAuditLog(dataSource).verify() instanceof AuditVerification.Ok);
    }

    @Test
    void aCommentWritesNoAuditEvent() {
        long id = seedCase(1);

        service.comment(id, "alice", "Chasing the bank");

        assertEquals(0, events(id).size());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM case_comment WHERE case_id = ?", Long.class, id));
    }

    @Test
    void assigningToTheCurrentOwnerChangesNothingAndWritesNoSecondEvent() {
        long id = seedCase(1);
        service.assign(id, 0, "alice", "lead", null);

        service.assign(id, 1, "alice", "lead", null);

        assertEquals(1, events(id).size());
    }

    @Test
    void aStaleVersionIsRefusedAndLeavesNoEvent() {
        long id = seedCase(1);
        service.assign(id, 0, "alice", "lead", null);

        assertThrows(CaseRejectedException.class, () -> service.assign(id, 0, "bob", "lead", null));

        assertEquals(1, events(id).size());
    }

    @Test
    void ifTheAuditEventCannotBeWrittenTheActionDoesNotHappenAtAll() {
        long id = seedCase(1);
        CaseService withBrokenAudit = new CaseService(new JdbcCaseStore(dataSource, new FailingAuditLog()),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(IllegalStateException.class, () -> withBrokenAudit.assign(id, 0, "alice", "lead", null));

        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM exception_case WHERE id = ?", String.class, id));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM exception_case WHERE id = ?", Long.class, id));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM case_activity WHERE case_id = ?", Long.class, id));
        assertEquals(0L,
                jdbc.queryForObject("SELECT count(*) FROM case_assignment WHERE case_id = ?", Long.class, id));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class));
    }

    private long seedCase(int ordinal) {
        String naturalKey = "NK-" + ordinal;
        jdbc.update(INSERT_EXCEPTION, runId, ordinal, naturalKey);
        return jdbc.queryForObject(INSERT_OPEN_CASE, Long.class, runId, ordinal, naturalKey);
    }

    private static List<Map<String, Object>> events(long caseId) {
        return jdbc.queryForList("SELECT action, actor, before_state, after_state, occurred_at_micros"
                + " FROM audit_event WHERE entity_type = 'exception_case' AND entity_id = ? ORDER BY seq",
                String.valueOf(caseId));
    }

    /** The canonical JSON of a case state: keys sorted, no spaces (ADR-0004). */
    private static String state(long id, String owner, String status, String team, String priority, String rule,
            long version) {
        return "{\"id\":" + id + ",\"owner\":" + (owner == null ? "null" : "\"" + owner + "\"")
                + ",\"priority\":\"" + priority + "\",\"routingRule\":\"" + rule + "\",\"status\":\"" + status
                + "\",\"team\":\"" + team + "\",\"version\":" + version + "}";
    }

    private static final class FailingAuditLog implements AuditLog {

        @Override
        public AuditEvent append(AuditDraft draft) {
            throw new IllegalStateException("audit log unavailable");
        }

        @Override
        public AuditHead head() {
            return AuditChain.GENESIS;
        }

        @Override
        public AuditVerification verify() {
            return new AuditVerification.Ok(0, 0, AuditChain.GENESIS_HASH);
        }
    }
}