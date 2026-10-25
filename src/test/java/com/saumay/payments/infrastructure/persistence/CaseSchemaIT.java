package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the V4 case schema on PostgreSQL 17 (ADR-0007): the database itself allows only the eight legal
 * transitions of FRD-07, keeps a resolved case final, freezes identity and SLA columns, refuses a second case for the
 * same exception (AC-06.3) and keeps the history tables append-only.
 */
@Testcontainers
class CaseSchemaIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String RESTRICT_VIOLATION = "23001";

    private static final List<String> STATES = List.of("OPEN", "ASSIGNED", "INVESTIGATING", "RESOLVED", "ESCALATED");
    private static final Set<String> LEGAL = Set.of(
            "OPEN>ASSIGNED", "OPEN>ESCALATED",
            "ASSIGNED>INVESTIGATING", "ASSIGNED>ESCALATED",
            "INVESTIGATING>RESOLVED", "INVESTIGATING>ESCALATED",
            "ESCALATED>INVESTIGATING", "ESCALATED>ASSIGNED");

    private long runId;
    private int keyCounter;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @BeforeEach
    void resetAndCreateARunWithOneException() throws SQLException {
        // TRUNCATE is not blocked by the row-level triggers; CASCADE reaches cases and their history.
        execute("TRUNCATE reconciliation_run RESTART IDENTITY CASCADE");
        keyCounter = 0;
        try (Connection c = connect(); Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("INSERT INTO reconciliation_run (business_date, status,"
                    + " ledger_cutoff_seq, auto_threshold_milli, review_threshold_milli, date_window_business_days,"
                    + " lookback_business_days, max_one_to_many_candidates, fuzzy_min_similarity_milli,"
                    + " fuzzy_counterparty_weight_milli) VALUES (DATE '2026-10-09', 'CREATED', 0, 950, 700, 3, 10, 8,"
                    + " 600, 600) RETURNING id")) {
                r.next();
                runId = r.getLong(1);
            }
            s.executeUpdate("INSERT INTO run_exception (run_id, ordinal, kind, natural_key, reason, detail)"
                    + " VALUES (" + runId + ", 1, 'MISSING_LEDGER', 'nk-0', 'no ledger entry', 'none')");
        }
    }

    @Test
    void migrationCreatesTheFiveCaseTables() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"
                     + " AND table_name IN ('exception_case', 'case_activity', 'case_assignment', 'case_comment',"
                     + " 'case_attachment')")) {
            r.next();
            assertEquals(5, r.getInt(1));
        }
    }

    @Test
    void aNewCaseStartsOpenAtVersionZero() throws SQLException {
        long id = insertCase("OPEN");
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT status, version FROM exception_case WHERE id = " + id)) {
            r.next();
            assertEquals("OPEN", r.getString(1));
            assertEquals(0L, r.getLong(2));
        }
    }

    @Test
    void exactlyTheEightLegalTransitionsSucceedAtTheDatabase() throws SQLException {
        List<String> problems = new ArrayList<>();
        int succeeded = 0;
        for (String from : STATES) {
            for (String to : STATES) {
                if (from.equals(to)) {
                    continue; // a same-state update is not a transition; AC-07.5 is asserted in the domain test
                }
                boolean legal = LEGAL.contains(from + ">" + to);
                long id = insertCase(from);
                try {
                    transition(id, to);
                    if (legal) {
                        succeeded++;
                    } else {
                        problems.add(from + " -> " + to + " was accepted");
                    }
                } catch (SQLException e) {
                    String expected = from.equals("RESOLVED") ? "final" : "illegal case transition";
                    if (legal) {
                        problems.add(from + " -> " + to + " was rejected: " + e.getMessage());
                    } else if (!e.getMessage().contains(expected)) {
                        problems.add(from + " -> " + to + " failed for the wrong reason: " + e.getMessage());
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
        assertEquals(8, succeeded);
    }

    @Test
    void aRerunCannotOpenASecondCaseForTheSameException() throws SQLException {
        insertCaseRow("same-key", "MISSING_LEDGER", "BREAK", "OPEN", null, null, null);
        assertRejected(UNIQUE_VIOLATION, null,
                () -> insertCaseRow("same-key", "MISSING_LEDGER", "BREAK", "OPEN", null, null, null));
    }

    @Test
    void anUnmatchCaseMayShareTheKeyOfItsResolvedPredecessor() throws SQLException {
        long resolved = insertCaseRow("shared-key", "MISSING_LEDGER", "BREAK", "RESOLVED", null,
                "MANUAL_MATCH", "matched by hand");
        insertUnmatchCase(String.valueOf(resolved), "shared-key");
    }

    @Test
    void originRulesAreEnforced() {
        assertRejected(CHECK_VIOLATION, null, () -> insertUnmatchCase("NULL", "k-1"));
        assertRejected(FOREIGN_KEY_VIOLATION, null, () -> execute("INSERT INTO exception_case (origin, origin_run_id,"
                + " origin_ordinal, natural_key, kind, case_class, amount_minor, currency, priority, team,"
                + " routing_rule, created_business_date, sla_business_days, due_business_date) VALUES ('RUN', "
                + runId + ", 99, 'nk-x', 'MISSING_LEDGER', 'BREAK', 1, 'GBP', 'P3', 'LEDGER_OPERATIONS', 'r',"
                + " DATE '2026-10-09', 5, DATE '2026-10-16')"));
    }

    @Test
    void aResolvedCaseIsFinal() throws SQLException {
        long id = insertCase("RESOLVED");
        assertRejected(RESTRICT_VIOLATION, "final",
                () -> execute("UPDATE exception_case SET routing_rule = 'x', version = version + 1 WHERE id = " + id));
    }

    @Test
    void theVersionMustRiseByExactlyOne() throws SQLException {
        long id = insertCase("OPEN");
        assertRejected(CHECK_VIOLATION, "version",
                () -> execute("UPDATE exception_case SET routing_rule = 'x', version = version + 2 WHERE id = " + id));
        assertRejected(CHECK_VIOLATION, "version",
                () -> execute("UPDATE exception_case SET routing_rule = 'x' WHERE id = " + id));
    }

    @Test
    void identityAmountAndSlaColumnsAreImmutable() throws SQLException {
        long id = insertCase("OPEN");
        assertRejected(RESTRICT_VIOLATION, "immutable",
                () -> execute("UPDATE exception_case SET amount_minor = 1, version = version + 1 WHERE id = " + id));
        assertRejected(RESTRICT_VIOLATION, "immutable", () -> execute("UPDATE exception_case SET"
                + " due_business_date = DATE '2026-12-31', version = version + 1 WHERE id = " + id));
    }

    @Test
    void casesCanNeverBeDeleted() throws SQLException {
        long id = insertCase("OPEN");
        assertRejected(RESTRICT_VIOLATION, "never deleted", () -> execute("DELETE FROM exception_case WHERE id = " + id));
    }

    @Test
    void caseClassMustAgreeWithTheExceptionKind() {
        assertRejected(CHECK_VIOLATION, null,
                () -> insertCaseRow("c-1", "LOW_CONFIDENCE", "BREAK", "OPEN", null, null, null));
        assertRejected(CHECK_VIOLATION, null,
                () -> insertCaseRow("c-2", "AMBIGUOUS_TIE", "BREAK", "OPEN", null, null, null));
        assertRejected(CHECK_VIOLATION, null,
                () -> insertCaseRow("c-3", "MISSING_LEDGER", "REVIEW", "OPEN", null, null, null));
    }

    @Test
    void ownerRulesFollowTheStatus() {
        assertRejected(CHECK_VIOLATION, null,
                () -> insertCaseRow("o-1", "MISSING_LEDGER", "BREAK", "OPEN", "someone", null, null));
        assertRejected(CHECK_VIOLATION, null,
                () -> insertCaseRow("o-2", "MISSING_LEDGER", "BREAK", "ASSIGNED", null, null, null));
        assertRejected(CHECK_VIOLATION, null,
                () -> insertCaseRow("o-3", "MISSING_LEDGER", "BREAK", "INVESTIGATING", null, null, null));
    }

    @Test
    void aResolvedCaseNeedsAnAllowedResolutionAndAReason() {
        assertRejected(CHECK_VIOLATION, null,
                () -> insertCaseRow("r-1", "MISSING_LEDGER", "BREAK", "RESOLVED", null, "MANUAL_MATCH", null));
        assertRejected(CHECK_VIOLATION, null,
                () -> insertCaseRow("r-2", "MISSING_LEDGER", "BREAK", "RESOLVED", null, "WRITE_OFF", "not yet allowed"));
        assertRejected(CHECK_VIOLATION, null,
                () -> insertCaseRow("r-3", "MISSING_LEDGER", "BREAK", "OPEN", null, "SPLIT", "too early"));
    }

    @Test
    void theHistoryTablesAreAppendOnly() throws SQLException {
        long id = insertCase("OPEN");
        execute("INSERT INTO case_activity (case_id, ordinal, activity_type, actor, occurred_at)"
                + " VALUES (" + id + ", 1, 'CREATED', 'system', now())");
        execute("INSERT INTO case_assignment (case_id, ordinal, owner, team, assigned_by, assigned_at)"
                + " VALUES (" + id + ", 1, 'analyst-1', 'RECON_ANALYSTS', 'lead-1', now())");
        execute("INSERT INTO case_comment (case_id, ordinal, author, body, created_at)"
                + " VALUES (" + id + ", 1, 'analyst-1', 'Called the bank.', now())");
        execute("INSERT INTO case_attachment (case_id, ordinal, file_name, content_type, size_bytes, sha256, added_by,"
                + " added_at) VALUES (" + id + ", 1, 'swift.pdf', 'application/pdf', 10, '" + "a".repeat(64)
                + "', 'analyst-1', now())");

        for (String table : List.of("case_activity", "case_assignment", "case_comment", "case_attachment")) {
            assertRejected(RESTRICT_VIOLATION, null, () -> execute("DELETE FROM " + table));
        }
        assertRejected(RESTRICT_VIOLATION, null, () -> execute("UPDATE case_activity SET detail = 'x'"));
        assertRejected(RESTRICT_VIOLATION, null, () -> execute("UPDATE case_assignment SET reason = 'x'"));
        assertRejected(RESTRICT_VIOLATION, null, () -> execute("UPDATE case_comment SET body = 'x'"));
        assertRejected(RESTRICT_VIOLATION, null, () -> execute("UPDATE case_attachment SET file_name = 'x'"));
    }

    @Test
    void historyRowsMustBeWellFormedAndBelongToACase() throws SQLException {
        long id = insertCase("OPEN");
        assertRejected(FOREIGN_KEY_VIOLATION, null, () -> execute("INSERT INTO case_activity (case_id, ordinal,"
                + " activity_type, actor, occurred_at) VALUES (999999, 1, 'CREATED', 'system', now())"));
        assertRejected(CHECK_VIOLATION, null, () -> execute("INSERT INTO case_activity (case_id, ordinal,"
                + " activity_type, actor, occurred_at) VALUES (" + id + ", 1, 'INVENTED', 'system', now())"));
        assertRejected(CHECK_VIOLATION, null, () -> execute("INSERT INTO case_activity (case_id, ordinal,"
                + " activity_type, actor, from_status, occurred_at) VALUES (" + id
                + ", 1, 'STATUS_CHANGED', 'system', 'OPEN', now())"));
        assertRejected(CHECK_VIOLATION, null, () -> execute("INSERT INTO case_comment (case_id, ordinal, author, body,"
                + " created_at) VALUES (" + id + ", 1, 'analyst-1', '   ', now())"));
        assertRejected(CHECK_VIOLATION, null, () -> execute("INSERT INTO case_attachment (case_id, ordinal, file_name,"
                + " content_type, size_bytes, sha256, added_by, added_at) VALUES (" + id
                + ", 1, 'f.pdf', 'application/pdf', 1, 'xyz', 'analyst-1', now())"));
        execute("INSERT INTO case_comment (case_id, ordinal, author, body, created_at)"
                + " VALUES (" + id + ", 1, 'analyst-1', 'first', now())");
        assertRejected(UNIQUE_VIOLATION, null, () -> execute("INSERT INTO case_comment (case_id, ordinal, author,"
                + " body, created_at) VALUES (" + id + ", 1, 'analyst-2', 'second', now())"));
    }

    // ---- helpers ----

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static void assertRejected(String expectedState, String messagePart, Executable action) {
        SQLException e = assertThrows(SQLException.class, action);
        assertEquals(expectedState, e.getSQLState(), e.getMessage());
        if (messagePart != null) {
            assertTrue(e.getMessage().contains(messagePart), e.getMessage());
        }
    }

    private long insertCase(String status) throws SQLException {
        String owner = switch (status) {
            case "ASSIGNED", "INVESTIGATING", "ESCALATED" -> "analyst-1";
            default -> null;
        };
        boolean resolved = status.equals("RESOLVED");
        return insertCaseRow("nk-" + (++keyCounter), "MISSING_LEDGER", "BREAK", status, owner,
                resolved ? "MANUAL_MATCH" : null, resolved ? "matched by hand" : null);
    }

    private long insertCaseRow(String key, String kind, String caseClass, String status, String owner,
                               String resolutionType, String resolutionReason) throws SQLException {
        String resolvedAt = status.equals("RESOLVED") ? "now()" : "NULL";
        String sql = "INSERT INTO exception_case (origin, origin_run_id, origin_ordinal, natural_key, kind, case_class,"
                + " amount_minor, currency, status, priority, team, routing_rule, owner, created_business_date,"
                + " sla_business_days, due_business_date, resolution_type, resolution_reason, resolved_at)"
                + " VALUES ('RUN', ?, 1, ?, ?, ?, 12500, 'GBP', ?, 'P3', 'LEDGER_OPERATIONS', 'missing-ledger', ?,"
                + " DATE '2026-10-09', 5, DATE '2026-10-16', ?, ?, " + resolvedAt + ") RETURNING id";
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, runId);
            p.setString(2, key);
            p.setString(3, kind);
            p.setString(4, caseClass);
            p.setString(5, status);
            p.setString(6, owner);
            p.setString(7, resolutionType);
            p.setString(8, resolutionReason);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    private static void insertUnmatchCase(String linkedCaseIdOrNull, String key) throws SQLException {
        execute("INSERT INTO exception_case (origin, linked_case_id, natural_key, kind, case_class, amount_minor,"
                + " currency, priority, team, routing_rule, created_business_date, sla_business_days,"
                + " due_business_date) VALUES ('UNMATCH', " + linkedCaseIdOrNull + ", '" + key
                + "', 'MISSING_LEDGER', 'BREAK', 12500, 'GBP', 'P3', 'LEDGER_OPERATIONS', 'unmatch',"
                + " DATE '2026-10-09', 5, DATE '2026-10-16')");
    }

    private static void transition(long id, String to) throws SQLException {
        boolean takesOwner = to.equals("ASSIGNED") || to.equals("INVESTIGATING");
        boolean resolved = to.equals("RESOLVED");
        String sql = "UPDATE exception_case SET status = ?, owner = COALESCE(?, owner), version = version + 1,"
                + " resolution_type = ?, resolution_reason = ?, resolved_at = " + (resolved ? "now()" : "NULL")
                + " WHERE id = ?";
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, to);
            p.setString(2, takesOwner ? "analyst-1" : null);
            p.setString(3, resolved ? "MANUAL_MATCH" : null);
            p.setString(4, resolved ? "matched by hand" : null);
            p.setLong(5, id);
            p.executeUpdate();
        }
    }
}