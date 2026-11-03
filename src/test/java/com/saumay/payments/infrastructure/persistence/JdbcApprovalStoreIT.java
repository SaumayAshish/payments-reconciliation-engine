package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.application.ApprovalStore.Approval;
import com.saumay.payments.application.ApprovalStore.DecisionOutcome;
import com.saumay.payments.application.ApprovalStore.DeniedAttempt;
import com.saumay.payments.application.ApprovalStore.Rejection;
import com.saumay.payments.application.ApprovalStore.Submission;
import com.saumay.payments.application.ApprovalStore.SubmitOutcome;
import com.saumay.payments.application.ApprovalStore.SubmitResult;
import com.saumay.payments.application.AuditNote;
import com.saumay.payments.application.CaseMatchStore.Resolution;
import com.saumay.payments.domain.approval.ApprovalRequest;
import com.saumay.payments.domain.approval.ApprovalStatus;
import com.saumay.payments.domain.approval.ControlledAction;
import com.saumay.payments.domain.cases.ResolutionType;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
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
 * The approval transactions against a real PostgreSQL. The claimed-line path needs seeded statement and ledger lines
 * and is covered where those fixtures exist; here a resolution carries no lines, which exercises everything else.
 */
@Testcontainers
class JdbcApprovalStoreIT {

    private static final Instant AT = Instant.parse("2026-10-09T10:00:00Z");
    private static final String INSERT_CASE =
            "INSERT INTO exception_case (origin, natural_key, kind, case_class, amount_minor, currency, priority,"
                    + " team, routing_rule, created_business_date, sla_business_days, due_business_date, status,"
                    + " owner) VALUES ('UNMATCH', 'seed', 'MISSING_LEDGER', 'BREAK', 3000000, 'GBP', 'P3',"
                    + " 'LEDGER_OPERATIONS', 'missing-ledger', DATE '2026-10-09', 5, DATE '2026-10-16',"
                    + " 'INVESTIGATING', 'alice') RETURNING id";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static JdbcTemplate jdbc;
    private static JdbcApprovalStore store;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        DataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcApprovalStore(dataSource, new JdbcAuditLog(dataSource));
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE approval_request, exception_case RESTART IDENTITY CASCADE");
        jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER USER");
        jdbc.execute("TRUNCATE audit_event");
        jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER USER");
    }

    private long investigatingCase() {
        return jdbc.queryForObject(INSERT_CASE, Long.class);
    }

    private static ApprovalRequest request(long caseId) {
        return ApprovalRequest.pending(caseId, ControlledAction.WRITE_OFF, 3_000_000, "GBP", "stale item", "carol",
                AT, 0);
    }

    private static Submission submission(long caseId, long expectedVersion) {
        return new Submission(request(caseId), expectedVersion,
                id -> new AuditNote("APPROVAL_REQUESTED", "{\"status\":\"NONE\"}", "{\"id\":" + id + "}"));
    }

    private SubmitResult submitted(long caseId) {
        SubmitResult result = store.submit(submission(caseId, 0), AT);
        assertEquals(SubmitOutcome.SUBMITTED, result.outcome());
        return result;
    }

    private static Resolution resolution(long caseId, long version) {
        return new Resolution(caseId, version, ResolutionType.WRITE_OFF, "dave", "stale item", List.of(),
                new AuditNote("CASE_RESOLVED", "{\"id\":" + caseId + "}", "{\"id\":" + caseId + ",\"status\":\"RESOLVED\"}"));
    }

    private List<String> auditActions() {
        return jdbc.queryForList("SELECT action FROM audit_event ORDER BY seq", String.class);
    }

    private List<String> activityTypes(long caseId) {
        return jdbc.queryForList("SELECT activity_type FROM case_activity WHERE case_id = ? ORDER BY ordinal",
                String.class, caseId);
    }

    private String caseStatus(long caseId) {
        return jdbc.queryForObject("SELECT status FROM exception_case WHERE id = ?", String.class, caseId);
    }

    @Test
    void submittingWritesTheRequestItsTimelineEntryAndOneAuditEvent() {
        long caseId = investigatingCase();
        SubmitResult result = submitted(caseId);
        ApprovalRequest stored = store.find(result.id()).orElseThrow();
        assertEquals(ApprovalStatus.PENDING, stored.status());
        assertEquals("carol", stored.maker());
        assertEquals(3_000_000, stored.amountMinor());
        assertEquals(List.of("APPROVAL_REQUESTED"), activityTypes(caseId));
        assertEquals(List.of("APPROVAL_REQUESTED"), auditActions());
        assertEquals(result.id(), store.findPendingForCase(caseId).orElseThrow().id());
    }

    @Test
    void aSecondPendingRequestForTheSameCaseWritesNothing() {
        long caseId = investigatingCase();
        submitted(caseId);
        assertEquals(SubmitOutcome.ALREADY_PENDING, store.submit(submission(caseId, 0), AT).outcome());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM approval_request", Integer.class));
        assertEquals(List.of("APPROVAL_REQUESTED"), auditActions());
        assertEquals(List.of("APPROVAL_REQUESTED"), activityTypes(caseId));
    }

    @Test
    void aStaleCaseVersionWritesNothing() {
        long caseId = investigatingCase();
        assertEquals(SubmitOutcome.VERSION_CONFLICT, store.submit(submission(caseId, 5), AT).outcome());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM approval_request", Integer.class));
        assertTrue(auditActions().isEmpty());
    }

    @Test
    void approvingDecidesTheRequestAndResolvesTheCaseTogether() {
        long caseId = investigatingCase();
        long id = submitted(caseId).id();
        ApprovalRequest decided = store.find(id).orElseThrow().approve("dave", AT, "evidence seen");
        AuditNote note = new AuditNote("APPROVAL_APPROVED", "{}", "{}");
        assertEquals(DecisionOutcome.DECIDED, store.approve(new Approval(decided, resolution(caseId, 0), note), AT));

        assertEquals("RESOLVED", caseStatus(caseId));
        assertEquals("WRITE_OFF", jdbc.queryForObject("SELECT resolution_type FROM exception_case WHERE id = ?",
                String.class, caseId));
        ApprovalRequest stored = store.find(id).orElseThrow();
        assertEquals(ApprovalStatus.APPROVED, stored.status());
        assertEquals("dave", stored.checker());
        assertEquals(List.of("APPROVAL_REQUESTED", "APPROVAL_APPROVED", "RESOLVED"), activityTypes(caseId));
        assertEquals(List.of("APPROVAL_REQUESTED", "CASE_RESOLVED", "APPROVAL_APPROVED"), auditActions());
    }

    @Test
    void ifTheCaseCannotBeResolvedTheApprovalStaysPendingAndNothingIsWritten() {
        long caseId = investigatingCase();
        long id = submitted(caseId).id();
        ApprovalRequest decided = store.find(id).orElseThrow().approve("dave", AT, null);
        AuditNote note = new AuditNote("APPROVAL_APPROVED", "{}", "{}");
        assertEquals(DecisionOutcome.VERSION_CONFLICT,
                store.approve(new Approval(decided, resolution(caseId, 9), note), AT));

        assertEquals("INVESTIGATING", caseStatus(caseId));
        assertEquals(ApprovalStatus.PENDING, store.find(id).orElseThrow().status());
        assertEquals(List.of("APPROVAL_REQUESTED"), activityTypes(caseId));
        assertEquals(List.of("APPROVAL_REQUESTED"), auditActions());
    }

    @Test
    void rejectingRecordsTheDecisionAndKeepsTheCaseOpenForAnotherRequest() {
        long caseId = investigatingCase();
        long id = submitted(caseId).id();
        ApprovalRequest decided = store.find(id).orElseThrow().reject("dave", AT, "no supporting evidence");
        AuditNote note = new AuditNote("APPROVAL_REJECTED", "{}", "{}");
        assertEquals(DecisionOutcome.DECIDED, store.reject(new Rejection(decided, note), AT));

        assertEquals("INVESTIGATING", caseStatus(caseId));
        assertEquals(ApprovalStatus.REJECTED, store.find(id).orElseThrow().status());
        assertEquals(List.of("APPROVAL_REQUESTED", "APPROVAL_REJECTED"), activityTypes(caseId));
        assertEquals(List.of("APPROVAL_REQUESTED", "APPROVAL_REJECTED"), auditActions());
        assertEquals(SubmitOutcome.SUBMITTED, store.submit(submission(caseId, 0), AT).outcome());
    }

    @Test
    void aSecondDecisionOnTheSameRequestIsReportedAndWritesNothing() {
        long caseId = investigatingCase();
        long id = submitted(caseId).id();
        ApprovalRequest first = store.find(id).orElseThrow().reject("dave", AT, "no");
        AuditNote note = new AuditNote("APPROVAL_REJECTED", "{}", "{}");
        store.reject(new Rejection(first, note), AT);
        ApprovalRequest second = store.find(id).orElseThrow();
        ApprovalRequest stale = new ApprovalRequest(id, caseId, ControlledAction.WRITE_OFF, 3_000_000, "GBP",
                "stale item", "carol", AT, 0, ApprovalStatus.APPROVED, "erin", AT, null);
        assertEquals(DecisionOutcome.ALREADY_DECIDED,
                store.approve(new Approval(stale, resolution(caseId, 0), note), AT));
        assertEquals(ApprovalStatus.REJECTED, second.status());
        assertEquals(2, auditActions().size());
    }

    @Test
    void aDeniedAttemptIsCommittedOnItsOwnAndLeavesTheRequestPending() {
        long caseId = investigatingCase();
        long id = submitted(caseId).id();
        store.recordDenied(new DeniedAttempt(id, "carol", new AuditNote("APPROVAL_DENIED", "{}", "{}")), AT);
        assertEquals(List.of("APPROVAL_REQUESTED", "APPROVAL_DENIED"), auditActions());
        assertEquals(ApprovalStatus.PENDING, store.find(id).orElseThrow().status());
        assertEquals("carol", jdbc.queryForObject("SELECT actor FROM audit_event WHERE action = 'APPROVAL_DENIED'",
                String.class));
    }
}