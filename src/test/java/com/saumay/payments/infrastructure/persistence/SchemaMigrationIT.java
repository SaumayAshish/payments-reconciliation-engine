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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the V1 schema on the PostgreSQL version we target (17): Flyway applies it, constraints reject bad rows,
 * and the append-only triggers block UPDATE and DELETE on input tables (ADR-0003, FRD-01, FRD-02).
 */
@Testcontainers
class SchemaMigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String KEY_1 = "1".repeat(64);
    private static final String KEY_2 = "2".repeat(64);

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String RESTRICT_VIOLATION = "23001";

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @BeforeEach
    void emptyTheTables() throws SQLException {
        // TRUNCATE is deliberately not blocked by the immutability triggers, so tests can reset state.
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE rejected_line, statement_line, ledger_entry, ingestion_file RESTART IDENTITY CASCADE");
        }
    }

    @Test
    void migrationRunsOnPostgres17AndCreatesTheInputTables() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            try (ResultSet version = s.executeQuery("SHOW server_version")) {
                version.next();
                assertTrue(version.getString(1).startsWith("17"), "expected PostgreSQL 17");
            }
            try (ResultSet tables = s.executeQuery("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name IN ('ingestion_file', 'statement_line', 'ledger_entry', 'rejected_line')
                    """)) {
                tables.next();
                assertEquals(4, tables.getInt(1));
            }
        }
    }

    @Test
    void sameFileContentCannotBeIngestedTwiceForTheSameKind() throws SQLException {
        insertFile("STATEMENT", HASH_A);
        assertEquals(UNIQUE_VIOLATION, sqlStateOf(() -> insertFile("STATEMENT", HASH_A)));
    }

    @Test
    void sameBytesAsAStatementAndAsALedgerAreTwoDifferentIngestions() throws SQLException {
        long statement = insertFile("STATEMENT", HASH_A);
        long ledger = insertFile("LEDGER", HASH_A);
        assertTrue(statement != ledger);
    }

    @Test
    void aLineKeyCanOnlyExistOnce() throws SQLException {
        long file = insertFile("STATEMENT", HASH_A);
        insertStatementLine(file, 1, KEY_1, 100, "GBP");
        assertEquals(UNIQUE_VIOLATION, sqlStateOf(() -> insertStatementLine(file, 2, KEY_1, 100, "GBP")));
    }

    @Test
    void aLineNumberCanOnlyExistOncePerFile() throws SQLException {
        long file = insertFile("STATEMENT", HASH_A);
        insertStatementLine(file, 1, KEY_1, 100, "GBP");
        assertEquals(UNIQUE_VIOLATION, sqlStateOf(() -> insertStatementLine(file, 1, KEY_2, 100, "GBP")));
    }

    @Test
    void zeroAmountsAndMalformedCurrenciesAreRejectedByTheDatabase() throws SQLException {
        long file = insertFile("STATEMENT", HASH_A);
        assertEquals(CHECK_VIOLATION, sqlStateOf(() -> insertStatementLine(file, 1, KEY_1, 0, "GBP")));
        assertEquals(CHECK_VIOLATION, sqlStateOf(() -> insertStatementLine(file, 1, KEY_1, 100, "gbp")));
    }

    @Test
    void malformedFileHashesAndUnknownKindsAreRejected() {
        assertEquals(CHECK_VIOLATION, sqlStateOf(() -> insertFile("STATEMENT", "not-a-hash")));
        assertEquals(CHECK_VIOLATION, sqlStateOf(() -> insertFile("OTHER", HASH_B)));
    }

    @Test
    void aLineMustBelongToAnExistingIngestion() {
        assertEquals(FOREIGN_KEY_VIOLATION, sqlStateOf(() -> insertStatementLine(999_999L, 1, KEY_1, 100, "GBP")));
    }

    @Test
    void ingestedLinesCannotBeUpdatedOrDeleted() throws SQLException {
        long file = insertFile("STATEMENT", HASH_A);
        insertStatementLine(file, 1, KEY_1, 100, "GBP");

        assertEquals(RESTRICT_VIOLATION, sqlStateOf(() -> execute("UPDATE statement_line SET amount_minor = 999")));
        assertEquals(RESTRICT_VIOLATION, sqlStateOf(() -> execute("DELETE FROM statement_line")));
        assertEquals(RESTRICT_VIOLATION, sqlStateOf(() -> execute("UPDATE ingestion_file SET accepted_count = 5")));
        assertEquals(RESTRICT_VIOLATION, sqlStateOf(() -> execute("DELETE FROM ingestion_file")));
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

    private static String sqlStateOf(Executable action) {
        return assertThrows(SQLException.class, action).getSQLState();
    }

    private static long insertFile(String kind, String hash) throws SQLException {
        String sql = "INSERT INTO ingestion_file (kind, file_hash, format, byte_size, accepted_count, rejected_count)"
                + " VALUES (?, ?, 'CSV', 10, 1, 0) RETURNING id";
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, kind);
            p.setString(2, hash);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    private static void insertStatementLine(long ingestionId, int lineNumber, String lineKey, long amountMinor,
                                            String currency) throws SQLException {
        String sql = "INSERT INTO statement_line (ingestion_id, line_number, line_key, account, amount_minor,"
                + " currency, value_date, reference, reference_norm)"
                + " VALUES (?, ?, ?, 'ACC-1', ?, ?, DATE '2026-10-09', 'INV-1', 'INV1')";
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, ingestionId);
            p.setInt(2, lineNumber);
            p.setString(3, lineKey);
            p.setLong(4, amountMinor);
            p.setString(5, currency);
            p.executeUpdate();
        }
    }
}