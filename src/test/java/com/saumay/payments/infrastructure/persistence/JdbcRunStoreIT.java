package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.application.RunErrorCode;
import com.saumay.payments.application.RunHeader;
import com.saumay.payments.application.RunInputs;
import com.saumay.payments.application.RunRejectedException;
import com.saumay.payments.application.RunRequest;
import com.saumay.payments.application.RunStatus;
import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.ingestion.AcceptedLine;
import com.saumay.payments.ingestion.FileFormat;
import com.saumay.payments.ingestion.IngestionOutcome;
import com.saumay.payments.ingestion.IngestionRequest;
import com.saumay.payments.ingestion.normalise.LineNormaliser;
import com.saumay.payments.ingestion.normalise.RawLine;
import com.saumay.payments.matching.MatchingConfig;
import com.saumay.payments.matching.ReconciliationRun;
import com.saumay.payments.matching.RunResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

/** FRD-10 against a real PostgreSQL 17: a run's inputs are fixed, its output is complete or absent, and it never skips a row. */
@Testcontainers
class JdbcRunStoreIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 9);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static JdbcTemplate jdbc;
    private static JdbcIngestionStore ingestion;
    private static JdbcRunStore runs;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        ingestion = new JdbcIngestionStore(dataSource);
        runs = new JdbcRunStore(dataSource);
    }

    @BeforeEach
    void emptyTheTables() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_slow_ledger ON ledger_entry");
        jdbc.execute("TRUNCATE line_claim, case_attachment, case_comment, case_assignment, case_activity, exception_case, run_note, run_carried_forward, run_exception_line, run_exception, run_match_line,"
                + " run_match, reconciliation_run_statement_batch, reconciliation_run, rejected_line,"
                + " statement_line, ledger_entry, ingestion_file RESTART IDENTITY");
    }

    @Test
    void aRunFixesItsInputsWhenItIsCreated() {
        long statements = ingest(LineSource.STATEMENT, "stmt-1", raw("A-1", "10.00", "2026-10-09")).ingestionId();
        ingest(LineSource.LEDGER, "ledger-1", raw("A-1", "10.00", "2026-10-09"), raw("A-2", "20.00", "2026-10-09"));

        RunHeader run = runs.create(request(statements));

        assertEquals(RunStatus.CREATED, run.status());
        assertEquals(2, run.ledgerCutoffSeq());
        assertEquals(List.of(statements), run.statementIngestionIds());
        assertEquals(BUSINESS_DATE, run.businessDate());
        assertEquals(MatchingConfig.defaults(), run.config());
        assertNull(run.startedAt());
        assertNull(run.outputHash());
        assertNull(run.summary());
        assertNotNull(run.createdAt());
    }

    @Test
    void aRunNamingAnUnknownIngestionIsRefusedAndStoresNothing() {
        RunRejectedException refused =
                assertThrows(RunRejectedException.class, () -> runs.create(request(999)));

        assertEquals(RunErrorCode.UNKNOWN_INGESTION, refused.code());
        assertEquals(0, count("reconciliation_run"));
    }

    @Test
    void aRunNamingALedgerIngestionIsRefused() {
        long ledger = ingest(LineSource.LEDGER, "ledger-1", raw("A-1", "10.00", "2026-10-09")).ingestionId();

        RunRejectedException refused =
                assertThrows(RunRejectedException.class, () -> runs.create(request(ledger)));

        assertEquals(RunErrorCode.NOT_A_STATEMENT_INGESTION, refused.code());
        assertEquals(0, count("reconciliation_run"));
    }

    @Test
    void linesIngestedAfterTheRunWasCreatedAreNotPartOfIt() {
        long named = ingest(LineSource.STATEMENT, "stmt-1", raw("A-1", "10.00", "2026-10-09")).ingestionId();
        ingest(LineSource.LEDGER, "ledger-1", raw("A-1", "10.00", "2026-10-09"));
        RunHeader run = runs.create(request(named));

        ingest(LineSource.LEDGER, "ledger-2", raw("B-1", "30.00", "2026-10-09"));
        ingest(LineSource.STATEMENT, "stmt-2", raw("B-1", "30.00", "2026-10-09"));

        RunInputs inputs = runs.loadInputs(run.id());
        assertEquals(1, inputs.statements().size());
        assertEquals(1, inputs.ledger().size());
        assertEquals("A1", inputs.ledger().get(0).line().referenceNorm());
    }

    @Test
    void aCompletedRunStoresEverythingItDecidedAndReplaysToTheSameHash() {
        long statements = ingest(LineSource.STATEMENT, "stmt-1",
                raw("REF-1", "10.00", "2026-10-09"),
                raw("REF-2", "20.00", "2026-10-09"),
                raw("ONLY-S", "30.00", "2026-10-09")).ingestionId();
        ingest(LineSource.LEDGER, "ledger-1",
                raw("REF-1", "10.00", "2026-10-09"),
                raw("REF-2", "20.00", "2026-10-14"),
                raw("ONLY-L", "40.00", "2026-10-08"));
        RunHeader created = runs.create(request(statements));

        RunInputs inputs = runs.loadInputs(created.id());
        runs.markRunning(created.id());
        RunResult result = ReconciliationRun.compute(inputs.statements(), inputs.ledger(), inputs.businessDate(),
                inputs.config());
        runs.complete(created.id(), result, 12);

        RunHeader stored = runs.find(created.id()).orElseThrow();
        assertEquals(RunStatus.COMPLETED, stored.status());
        assertEquals(result.outputHash().hex(), stored.outputHash());
        assertEquals(12L, stored.durationMillis());
        assertEquals(result.summary(), stored.summary());
        assertNotNull(stored.startedAt());
        assertNotNull(stored.completedAt());

        assertEquals(2, count("run_match"));
        assertEquals(4, count("run_match_line"));
        assertEquals(2, count("run_exception"));
        assertEquals(3, count("run_exception_line"));
        assertEquals(1, count("run_carried_forward"));

        RunInputs again = runs.loadInputs(created.id());
        RunResult replay = ReconciliationRun.compute(again.statements(), again.ledger(), again.businessDate(),
                again.config());
        assertEquals(stored.outputHash(), replay.outputHash().hex());
    }

    @Test
    void aRunCompletedTwiceKeepsExactlyOneSetOfOutput() {
        RunHeader run = runningRunWithOneExactMatch();
        RunInputs inputs = runs.loadInputs(run.id());
        RunResult result = ReconciliationRun.compute(inputs.statements(), inputs.ledger(), inputs.businessDate(),
                inputs.config());
        runs.complete(run.id(), result, 5);

        assertThrows(IllegalStateException.class, () -> runs.complete(run.id(), result, 5));
        assertThrows(IllegalStateException.class, () -> runs.markRunning(run.id()));
        assertEquals(1, count("run_match"));
        assertEquals(2, count("run_match_line"));
    }

    @Test
    void aFailedRunKeepsItsReasonAndCanNeverBeCompleted() {
        RunHeader run = runningRunWithOneExactMatch();

        runs.fail(run.id(), "matching crashed");

        RunHeader stored = runs.find(run.id()).orElseThrow();
        assertEquals(RunStatus.FAILED, stored.status());
        assertEquals("matching crashed", stored.failureReason());
        assertNotNull(stored.completedAt());
        assertNull(stored.outputHash());
        RunInputs inputs = runs.loadInputs(run.id());
        RunResult result = ReconciliationRun.compute(inputs.statements(), inputs.ledger(), inputs.businessDate(),
                inputs.config());
        assertThrows(IllegalStateException.class, () -> runs.complete(run.id(), result, 5));
        assertThrows(IllegalStateException.class, () -> runs.fail(run.id(), "again"));
        assertEquals(0, count("run_match"));
    }

    @Test
    void storedResultsAndRunDefinitionsCannotBeChangedAfterTheFact() {
        RunHeader run = runningRunWithOneExactMatch();
        RunInputs inputs = runs.loadInputs(run.id());
        runs.complete(run.id(), ReconciliationRun.compute(inputs.statements(), inputs.ledger(),
                inputs.businessDate(), inputs.config()), 5);

        RuntimeException resultEdit = assertThrows(RuntimeException.class,
                () -> jdbc.update("UPDATE run_match SET reason = 'edited'"));
        RuntimeException resultDelete = assertThrows(RuntimeException.class,
                () -> jdbc.update("DELETE FROM run_match_line"));
        RuntimeException cutoffEdit = assertThrows(RuntimeException.class,
                () -> jdbc.update("UPDATE reconciliation_run SET ledger_cutoff_seq = 0"));
        RuntimeException reopen = assertThrows(RuntimeException.class,
                () -> jdbc.update("UPDATE reconciliation_run SET status = 'RUNNING'"));

        assertTrue(resultEdit.getMessage().contains("append-only"));
        assertTrue(resultDelete.getMessage().contains("append-only"));
        assertTrue(cutoffEdit.getMessage().contains("definition of a reconciliation run cannot change"));
        assertTrue(reopen.getMessage().contains("illegal run status change"));
    }

    @Test
    void aRunWaitsForAnIngestionInFlightSoItsCutoffNeverSkipsARowThatCommitsLater() throws Exception {
        // The slow ingestion gets ledger seq 1 and then stays inside its transaction for 3 seconds. A fast
        // ingestion gets seq 2 and commits at once. Without the cut-off lock the run would read max(seq) = 2 and
        // load only the fast row, and the slow row (seq 1, below the cut-off) would commit afterwards and be
        // missing from the run for ever.
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION slow_ledger() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.reference LIKE 'SLOW%' THEN
                        PERFORM pg_sleep(3);
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("CREATE TRIGGER trg_slow_ledger BEFORE INSERT ON ledger_entry"
                + " FOR EACH ROW EXECUTE FUNCTION slow_ledger()");
        long statements = ingest(LineSource.STATEMENT, "stmt-1", raw("A-1", "10.00", "2026-10-09")).ingestionId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<IngestionOutcome> slow =
                    pool.submit(() -> ingest(LineSource.LEDGER, "slow", raw("SLOW-1", "5.00", "2026-10-09")));
            awaitABackendSleeping();
            ingest(LineSource.LEDGER, "fast", raw("FAST-1", "6.00", "2026-10-09"));

            Future<RunHeader> creating = pool.submit(() -> runs.create(request(statements)));
            Thread.sleep(500);
            assertFalse(creating.isDone(), "the run must wait for the ingestion that is still in flight");

            slow.get(30, TimeUnit.SECONDS);
            RunHeader run = creating.get(30, TimeUnit.SECONDS);

            assertEquals(2, run.ledgerCutoffSeq());
            assertEquals(2, runs.loadInputs(run.id()).ledger().size());
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- helpers ----

    private static RunHeader runningRunWithOneExactMatch() {
        long statements = ingest(LineSource.STATEMENT, "stmt-1", raw("A-1", "10.00", "2026-10-09")).ingestionId();
        ingest(LineSource.LEDGER, "ledger-1", raw("A-1", "10.00", "2026-10-09"));
        RunHeader run = runs.create(request(statements));
        runs.markRunning(run.id());
        return run;
    }

    private static RunRequest request(long statementIngestionId) {
        return new RunRequest(BUSINESS_DATE, List.of(statementIngestionId), MatchingConfig.defaults());
    }

    private static RawLine raw(String reference, String amount, String valueDate) {
        return new RawLine("ACC-1", null, valueDate, amount, "GBP", reference, "Acme Ltd", "payment");
    }

    private static IngestionOutcome ingest(LineSource kind, String fileText, RawLine... rawLines) {
        ContentHash hash = ContentHash.ofUtf8(fileText);
        List<AcceptedLine> lines = new ArrayList<>();
        for (int i = 0; i < rawLines.length; i++) {
            lines.add(new AcceptedLine(i + 1, LineNormaliser.normalise(kind, hash, i + 1, rawLines[i])));
        }
        return ingestion.store(new IngestionRequest(kind, hash, FileFormat.CSV, fileText.length(), lines, List.of()));
    }

    /** Waits until some database session is inside pg_sleep, i.e. the slow ingestion holds its sequence number. */
    private static void awaitABackendSleeping() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer sleeping = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event = 'PgSleep'", Integer.class);
            if (sleeping != null && sleeping > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("The slow ingestion never started");
    }

    private static int count(String table) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
    }
}