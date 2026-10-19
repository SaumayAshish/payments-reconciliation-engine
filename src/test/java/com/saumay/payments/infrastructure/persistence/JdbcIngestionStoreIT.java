package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.ingestion.AcceptedLine;
import com.saumay.payments.ingestion.FileFormat;
import com.saumay.payments.ingestion.IngestionOutcome;
import com.saumay.payments.ingestion.IngestionRequest;
import com.saumay.payments.ingestion.RejectedLine;
import com.saumay.payments.ingestion.normalise.LineErrorCode;
import com.saumay.payments.ingestion.normalise.LineNormaliser;
import com.saumay.payments.ingestion.normalise.RawLine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

/** FRD-01 acceptance criteria AC-01.1 to AC-01.6 against a real PostgreSQL 17. */
@Testcontainers
class JdbcIngestionStoreIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static JdbcTemplate jdbc;
    private static JdbcIngestionStore store;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcIngestionStore(dataSource);
    }

    @BeforeEach
    void emptyTheTables() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_simulated_crash ON statement_line");
        jdbc.execute("TRUNCATE rejected_line, statement_line, ledger_entry, ingestion_file RESTART IDENTITY");
    }

    @Test
    void firstUploadStoresEveryValidLineAndEveryRejectedLine() {
        IngestionOutcome outcome = store.store(request(LineSource.STATEMENT, "file-1", 3, 2));

        assertFalse(outcome.duplicate());
        assertEquals(3, outcome.acceptedCount());
        assertEquals(2, outcome.rejectedCount());
        assertEquals(1, count("ingestion_file"));
        assertEquals(3, count("statement_line"));
        assertEquals(2, count("rejected_line"));
    }

    @Test
    void tenUploadsOfTheSameBytesLeaveExactlyOneIngestion() {
        IngestionRequest request = request(LineSource.STATEMENT, "file-1", 3, 1);
        IngestionOutcome first = store.store(request);

        for (int i = 0; i < 9; i++) {
            IngestionOutcome again = store.store(request);
            assertTrue(again.duplicate());
            assertEquals(first.ingestionId(), again.ingestionId());
            assertEquals(3, again.acceptedCount());
            assertEquals(1, again.rejectedCount());
        }
        assertEquals(1, count("ingestion_file"));
        assertEquals(3, count("statement_line"));
        assertEquals(1, count("rejected_line"));
    }

    @Test
    void theSameBytesAsAStatementAndAsALedgerAreTwoIngestions() {
        IngestionOutcome statement = store.store(request(LineSource.STATEMENT, "same-bytes", 2, 0));
        IngestionOutcome ledger = store.store(request(LineSource.LEDGER, "same-bytes", 2, 0));

        assertFalse(statement.duplicate());
        assertFalse(ledger.duplicate());
        assertNotEquals(statement.ingestionId(), ledger.ingestionId());
        assertEquals(2, count("statement_line"));
        assertEquals(2, count("ledger_entry"));
    }

    @Test
    void concurrentIdenticalUploadsProduceExactlyOneIngestion() throws Exception {
        IngestionRequest request = request(LineSource.STATEMENT, "file-1", 50, 0);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IngestionOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return store.store(request);
            }));
        }
        start.countDown();

        int fresh = 0;
        Set<Long> ids = new HashSet<>();
        for (Future<IngestionOutcome> future : futures) {
            IngestionOutcome outcome = future.get(60, TimeUnit.SECONDS);
            if (!outcome.duplicate()) {
                fresh++;
            }
            ids.add(outcome.ingestionId());
        }
        pool.shutdownNow();

        assertEquals(1, fresh);
        assertEquals(1, ids.size());
        assertEquals(1, count("ingestion_file"));
        assertEquals(50, count("statement_line"));
    }

    @Test
    void aFailureHalfWayThroughLeavesNothingBehindAndARetryOfTheSameFileSucceeds() {
        // Simulates a crash while inserting line 3 of 5: the database raises an error mid-batch.
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION simulated_crash() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.line_number = 3 THEN
                        RAISE EXCEPTION 'simulated crash';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("CREATE TRIGGER trg_simulated_crash BEFORE INSERT ON statement_line"
                + " FOR EACH ROW EXECUTE FUNCTION simulated_crash()");
        IngestionRequest request = request(LineSource.STATEMENT, "file-1", 5, 1);

        assertThrows(RuntimeException.class, () -> store.store(request));
        assertEquals(0, count("ingestion_file"));
        assertEquals(0, count("statement_line"));
        assertEquals(0, count("rejected_line"));

        jdbc.execute("DROP TRIGGER trg_simulated_crash ON statement_line");
        IngestionOutcome retry = store.store(request);

        assertFalse(retry.duplicate());
        assertEquals(1, count("ingestion_file"));
        assertEquals(5, count("statement_line"));
        assertEquals(1, count("rejected_line"));
    }

    @Test
    void storedValuesMatchTheCanonicalLine() {
        store.store(request(LineSource.STATEMENT, "file-1", 1, 0));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM statement_line");
        assertEquals(1000L, row.get("amount_minor"));
        assertEquals("GBP", row.get("currency"));
        assertEquals("ACC-1", row.get("account"));
        assertEquals("REF-1", row.get("reference"));
        assertEquals("REF1", row.get("reference_norm"));
        assertEquals("ACME", row.get("counterparty_norm"));
        assertNull(row.get("booking_date"));
    }

    // ---- helpers ----

    private static IngestionRequest request(LineSource kind, String fileText, int accepted, int rejected) {
        ContentHash hash = ContentHash.ofUtf8(fileText);
        List<AcceptedLine> lines = new ArrayList<>();
        for (int i = 1; i <= accepted; i++) {
            RawLine raw = new RawLine("ACC-1", null, "2026-10-09", "10.00", "GBP", "REF-" + i, "Acme Ltd", "payment");
            lines.add(new AcceptedLine(i, LineNormaliser.normalise(kind, hash, i, raw)));
        }
        List<RejectedLine> bad = new ArrayList<>();
        for (int i = 1; i <= rejected; i++) {
            bad.add(new RejectedLine(accepted + i, LineErrorCode.INVALID_AMOUNT,
                    "amount is not a plain decimal number", "abc"));
        }
        return new IngestionRequest(kind, hash, FileFormat.CSV, fileText.length(), lines, bad);
    }

    private static int count(String table) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
    }
}