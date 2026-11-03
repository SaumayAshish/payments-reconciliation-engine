package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** What migration V9 guarantees on its own, with no application code in the way. */
@Testcontainers
class ApprovalSchemaIT {

    private static final String CASE_COLUMNS =
            "INSERT INTO exception_case (origin, natural_key, kind, case_class, amount_minor, currency, priority,"
                    + " team, routing_rule, created_business_date, sla_business_days, due_business_date";
    private static final String CASE_VALUES =
            "'UNMATCH', 'seed', 'MISSING_LEDGER', 'BREAK', 3000, 'GBP', 'P3', 'LEDGER_OPERATIONS', 'missing-ledger',"
                    + " DATE '2026-10-09', 5, DATE '2026-10-16'";
    private static final String PENDING_REQUEST =
            "INSERT INTO approval_request (case_id, action, amount_minor, currency, reason, maker)"
                    + " VALUES (?, 'WRITE_OFF', 3000, 'GBP', 'stale item', 'alice') RETURNING id";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void empty() {
        jdbc.execute("TRUNCATE approval_request, exception_case RESTART IDENTITY CASCADE");
    }

    private static String stateOf(Executable action) {
        DataAccessException failure = assertThrows(DataAccessException.class, action::execute);
        Throwable cause = failure;
        while (cause != null && !(cause instanceof SQLException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause, "expected a database error");
        return ((SQLException) cause).getSQLState();
    }

    private long openCase() {
        Long id = jdbc.queryForObject(CASE_COLUMNS + ") VALUES (" + CASE_VALUES + ") RETURNING id", Long.class);
        return id;
    }

    private void resolvedCase(String resolutionType) {
        jdbc.queryForObject(CASE_COLUMNS + ", status, resolution_type, resolution_reason, resolved_at) VALUES ("
                + CASE_VALUES + ", 'RESOLVED', '" + resolutionType + "', 'seeded', now()) RETURNING id", Long.class);
    }

    private long pendingRequest(long caseId) {
        Long id = jdbc.queryForObject(PENDING_REQUEST, Long.class, caseId);
        return id;
    }

    @Test
    void aCaseCanBeResolvedByWriteOffOrAdjustmentButNotByAnUnknownType() {
        assertDoesNotThrow(() -> resolvedCase("WRITE_OFF"));
        assertDoesNotThrow(() -> resolvedCase("ADJUSTMENT"));
        assertEquals("23514", stateOf(() -> resolvedCase("BOGUS")));
    }

    @Test
    void theClaimSourceConstraintNowAllowsTheControlledActionsAndTheOldColumnCheckIsGone() {
        Integer old = jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'line_claim_source_check'",
                Integer.class);
        assertEquals(0, old);
        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_claim_source'", String.class);
        assertTrue(definition.contains("WRITE_OFF") && definition.contains("ADJUSTMENT"), definition);
    }

    @Test
    void theSamePersonCannotBeMakerAndCheckerEvenWithDifferentCase() {
        long caseId = openCase();
        assertEquals("23514", stateOf(() -> jdbc.update(
                "INSERT INTO approval_request (case_id, action, amount_minor, currency, reason, maker, status, checker,"
                        + " decided_at) VALUES (?, 'WRITE_OFF', 3000, 'GBP', 'r', 'alice', 'APPROVED', 'ALICE', now())",
                caseId)));
    }

    @Test
    void aRejectionWithoutAReasonIsRefused() {
        long caseId = openCase();
        assertEquals("23514", stateOf(() -> jdbc.update(
                "INSERT INTO approval_request (case_id, action, amount_minor, currency, reason, maker, status, checker,"
                        + " decided_at) VALUES (?, 'WRITE_OFF', 3000, 'GBP', 'r', 'alice', 'REJECTED', 'bob', now())",
                caseId)));
    }

    @Test
    void onlyOnePendingRequestPerCaseButANewOneIsAllowedOnceTheFirstIsDecided() {
        long caseId = openCase();
        long first = pendingRequest(caseId);
        assertEquals("23505", stateOf(() -> pendingRequest(caseId)));
        jdbc.update("UPDATE approval_request SET status = 'REJECTED', checker = 'bob', decided_at = now(),"
                + " decision_note = 'no evidence' WHERE id = ?", first);
        assertDoesNotThrow(() -> pendingRequest(caseId));
    }

    @Test
    void aPendingRequestCanBeDecidedButNothingAboutWhatWasAskedCanChange() {
        long caseId = openCase();
        long id = pendingRequest(caseId);
        assertEquals("23001", stateOf(() -> jdbc.update("UPDATE approval_request SET amount_minor = 1 WHERE id = ?", id)));
        assertEquals("23001", stateOf(() -> jdbc.update("UPDATE approval_request SET maker = 'bob' WHERE id = ?", id)));
        assertEquals(1, jdbc.update("UPDATE approval_request SET status = 'APPROVED', checker = 'bob',"
                + " decided_at = now() WHERE id = ?", id));
    }

    @Test
    void aDecidedRequestIsFinalAndNoRequestCanBeDeleted() {
        long caseId = openCase();
        long id = pendingRequest(caseId);
        jdbc.update("UPDATE approval_request SET status = 'APPROVED', checker = 'bob', decided_at = now() WHERE id = ?", id);
        assertEquals("23001", stateOf(() -> jdbc.update("UPDATE approval_request SET decision_note = 'x' WHERE id = ?", id)));
        assertEquals("23001", stateOf(() -> jdbc.update("DELETE FROM approval_request WHERE id = ?", id)));
    }
}