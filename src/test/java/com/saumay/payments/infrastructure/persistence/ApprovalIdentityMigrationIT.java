package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies that V14 upgrades an existing V13 database without guessing
 * identities for historical approvals.
 */
@Testcontainers
class ApprovalIdentityMigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void existingApprovalsRemainLegacyAndProvenanceCannotBeRewritten() {
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("13"))
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        Long caseId = jdbc.queryForObject(
                "INSERT INTO exception_case"
                        + " (origin, natural_key, kind, case_class, amount_minor,"
                        + " currency, priority, team, routing_rule,"
                        + " created_business_date, sla_business_days, due_business_date)"
                        + " VALUES ('UNMATCH', 'identity-migration-test',"
                        + " 'MISSING_LEDGER', 'BREAK', 3000, 'GBP', 'P3',"
                        + " 'LEDGER_OPERATIONS', 'missing-ledger',"
                        + " DATE '2026-10-09', 5, DATE '2026-10-16')"
                        + " RETURNING id",
                Long.class);

        assertNotNull(caseId);

        Long legacyApprovalId = jdbc.queryForObject(
                "INSERT INTO approval_request"
                        + " (case_id, action, amount_minor, currency, reason, maker)"
                        + " VALUES (?, 'WRITE_OFF', 3000, 'GBP', 'reason', 'alice')"
                        + " RETURNING id",
                Long.class,
                caseId);

        assertNotNull(legacyApprovalId);

        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .load()
                .migrate();

        assertEquals("LEGACY", jdbc.queryForObject(
                "SELECT maker_identity_scheme FROM approval_request WHERE id = ?",
                String.class, legacyApprovalId));

        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM approval_request"
                        + " WHERE id = ? AND maker_issuer IS NOT NULL",
                Integer.class, legacyApprovalId));

        assertEquals("23001", sqlState(() -> jdbc.update(
                "UPDATE approval_request"
                        + " SET maker_identity_scheme = 'OIDC', maker_issuer = ?"
                        + " WHERE id = ?",
                "https://issuer.example.test", legacyApprovalId)));

        // Existing legitimate decision behavior remains available to the
        // legacy path; V14 does not rewrite or decide historical approvals.
        assertEquals(1, jdbc.update(
                "UPDATE approval_request SET status = 'REJECTED',"
                        + " checker = 'bob', decided_at = now(),"
                        + " decision_note = 'reason' WHERE id = ?",
                legacyApprovalId));

        assertEquals("23514", sqlState(() -> jdbc.update(
                "INSERT INTO approval_request"
                        + " (case_id, action, amount_minor, currency, reason, maker,"
                        + " maker_identity_scheme, maker_issuer)"
                        + " VALUES (?, 'WRITE_OFF', 3000, 'GBP', 'reason',"
                        + " 'oidc-maker', 'OIDC', NULL)",
                caseId)));

        Long oidcApprovalId = jdbc.queryForObject(
                "INSERT INTO approval_request"
                        + " (case_id, action, amount_minor, currency, reason, maker,"
                        + " maker_identity_scheme, maker_issuer)"
                        + " VALUES (?, 'WRITE_OFF', 3000, 'GBP', 'reason',"
                        + " 'oidc-maker', 'OIDC', ?) RETURNING id",
                Long.class,
                caseId,
                "https://issuer.example.test");

        assertNotNull(oidcApprovalId);

        assertEquals("OIDC", jdbc.queryForObject(
                "SELECT maker_identity_scheme FROM approval_request WHERE id = ?",
                String.class, oidcApprovalId));

        assertEquals("https://issuer.example.test", jdbc.queryForObject(
                "SELECT maker_issuer FROM approval_request WHERE id = ?",
                String.class, oidcApprovalId));

        assertEquals("23001", sqlState(() -> jdbc.update(
                "UPDATE approval_request SET maker_issuer = ? WHERE id = ?",
                "https://other.example.test", oidcApprovalId)));
    }

    private static String sqlState(org.junit.jupiter.api.function.Executable action) {
        DataAccessException failure = assertThrows(DataAccessException.class, action);
        Throwable cause = failure;

        while (cause != null && !(cause instanceof SQLException)) {
            cause = cause.getCause();
        }

        assertNotNull(cause, "Expected a database error");
        return ((SQLException) cause).getSQLState();
    }
}