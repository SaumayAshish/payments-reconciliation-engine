package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.ingestion.AcceptedLine;
import com.saumay.payments.ingestion.FileFormat;
import com.saumay.payments.ingestion.IngestionOutcome;
import com.saumay.payments.ingestion.IngestionRequest;
import com.saumay.payments.ingestion.normalise.LineNormaliser;
import com.saumay.payments.ingestion.normalise.RawLine;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

/**
 * ADR-0009 at the schema level: a line has at most one active claim whoever writes it, a release must name a claim
 * on the same line and can be used once, history is append-only, and the run's claim cut-off is part of its
 * immutable definition.
 */
@Testcontainers
class ClaimSchemaIT {

    private static final String RUN_CLAIM =
            "INSERT INTO line_claim (action, %s, source, run_id, run_match_ordinal)"
                    + " VALUES ('CLAIM', ?, 'RUN_MATCH', ?, 1) RETURNING seq";
    private static final String MANUAL_CLAIM =
            "INSERT INTO line_claim (action, %s, source, case_id, actor)"
                    + " VALUES ('CLAIM', ?, 'MANUAL_MATCH', ?, 'alice') RETURNING seq";
    private static final String RELEASE =
            "INSERT INTO line_claim (action, %s, source, case_id, actor, releases_seq)"
                    + " VALUES ('RELEASE', ?, 'UNMATCH', ?, 'bob', ?) RETURNING seq";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static JdbcTemplate jdbc;
    private static JdbcIngestionStore ingestion;

    private long runId;
    private long caseId;
    private List<Long> statements;
    private List<Long> ledger;

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
    }

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE line_claim, case_attachment, case_comment, case_assignment, case_activity,"
                + " exception_case, run_note, run_carried_forward, run_exception_line, run_exception, run_match_line,"
                + " run_match, reconciliation_run_statement_batch, reconciliation_run, rejected_line,"
                + " statement_line, ledger_entry, ingestion_file RESTART IDENTITY CASCADE");
        ingest(LineSource.STATEMENT, "stmt-1", raw("REF-1", "10.00"), raw("REF-2", "20.00"));
        ingest(LineSource.LEDGER, "ledger-1", raw("REF-1", "10.00"), raw("REF-2", "20.00"));
        statements = jdbc.queryForList("SELECT seq FROM statement_line ORDER BY seq", Long.class);
        ledger = jdbc.queryForList("SELECT seq FROM ledger_entry ORDER BY seq", Long.class);
        runId = jdbc.queryForObject(
                "INSERT INTO reconciliation_run (business_date, status, ledger_cutoff_seq, auto_threshold_milli,"
                        + " review_threshold_milli, date_window_business_days, lookback_business_days,"
                        + " max_one_to_many_candidates, fuzzy_min_similarity_milli, fuzzy_counterparty_weight_milli)"
                        + " VALUES (DATE '2026-10-09', 'CREATED', 0, 950, 700, 3, 10, 8, 600, 600) RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO run_match (run_id, ordinal, tier, score_milli, band, reason)"
                + " VALUES (?, 1, 'EXACT', 1000, 'AUTO_CONFIRM', 'r')", runId);
        jdbc.update("INSERT INTO run_exception (run_id, ordinal, kind, natural_key, reason, detail)"
                + " VALUES (?, 1, 'MISSING_LEDGER', 'MISSING_LEDGER:nk-1', 'r', 'd')", runId);
        caseId = jdbc.queryForObject(
                "INSERT INTO exception_case (origin, origin_run_id, origin_ordinal, natural_key, kind, case_class,"
                        + " amount_minor, currency, priority, team, routing_rule, created_business_date,"
                        + " sla_business_days, due_business_date) VALUES ('RUN', ?, 1, 'MISSING_LEDGER:nk-1',"
                        + " 'MISSING_LEDGER', 'BREAK', 3000, 'GBP', 'P3', 'LEDGER_OPERATIONS', 'missing-ledger',"
                        + " DATE '2026-10-09', 5, DATE '2026-10-16') RETURNING id", Long.class, runId);
    }

    @Test
    void aLedgerEntryHasAtMostOneActiveClaim() {
        claimByRun("ledger_seq", ledger.get(0));

        SQLException failure = failure(() -> claimManually("ledger_seq", ledger.get(0)));

        assertEquals("23505", failure.getSQLState());
    }

    @Test
    void aStatementLineHasAtMostOneActiveClaim() {
        claimManually("statement_seq", statements.get(0));

        SQLException failure = failure(() -> claimByRun("statement_seq", statements.get(0)));

        assertEquals("23505", failure.getSQLState());
    }

    @Test
    void differentLinesAreClaimedIndependently() {
        claimByRun("ledger_seq", ledger.get(0));
        claimByRun("ledger_seq", ledger.get(1));
        claimByRun("statement_seq", statements.get(0));
        assertEquals(3, count("line_claim"));
    }

    @Test
    void aReleasedLineCanBeClaimedAgain() {
        long claim = claimByRun("ledger_seq", ledger.get(0));
        release("ledger_seq", ledger.get(0), claim);

        claimManually("ledger_seq", ledger.get(0));

        assertEquals(3, count("line_claim"));
    }

    @Test
    void aReleaseMustNameAClaimOnTheSameLine() {
        long claim = claimByRun("ledger_seq", ledger.get(0));

        SQLException failure = failure(() -> release("ledger_seq", ledger.get(1), claim));

        assertEquals("23514", failure.getSQLState());
        assertEquals(1, count("line_claim"));
    }

    @Test
    void aClaimCanBeReleasedOnlyOnce() {
        long claim = claimByRun("ledger_seq", ledger.get(0));
        release("ledger_seq", ledger.get(0), claim);

        SQLException failure = failure(() -> release("ledger_seq", ledger.get(0), claim));

        assertEquals("23505", failure.getSQLState());
    }

    @Test
    void aReleaseCannotNameAnotherRelease() {
        long claim = claimByRun("ledger_seq", ledger.get(0));
        long release = release("ledger_seq", ledger.get(0), claim);

        SQLException failure = failure(() -> release("ledger_seq", ledger.get(0), release));

        assertEquals("23514", failure.getSQLState());
    }

    @Test
    void aRowMustTouchExactlyOneSideAndMatchItsSource() {
        assertEquals("23514", failure(() -> jdbc.update("INSERT INTO line_claim (action, statement_seq, ledger_seq,"
                + " source, run_id, run_match_ordinal) VALUES ('CLAIM', ?, ?, 'RUN_MATCH', ?, 1)",
                statements.get(0), ledger.get(0), runId)).getSQLState());
        assertEquals("23514", failure(() -> jdbc.update("INSERT INTO line_claim (action, ledger_seq, source)"
                + " VALUES ('CLAIM', ?, 'RUN_MATCH')", ledger.get(0))).getSQLState());
        assertEquals("23514", failure(() -> jdbc.update("INSERT INTO line_claim (action, ledger_seq, source, case_id)"
                + " VALUES ('CLAIM', ?, 'MANUAL_MATCH', ?)", ledger.get(0), caseId)).getSQLState());
        assertEquals(0, count("line_claim"));
    }

    @Test
    void claimHistoryIsAppendOnly() {
        long claim = claimByRun("ledger_seq", ledger.get(0));

        assertEquals("23001", failure(() -> jdbc.update("UPDATE line_claim SET actor = 'x' WHERE seq = ?", claim))
                .getSQLState());
        assertEquals("23001", failure(() -> jdbc.update("DELETE FROM line_claim WHERE seq = ?", claim))
                .getSQLState());
        assertEquals(1, count("line_claim"));
    }

    @Test
    void whenTwoWritersClaimTheSameLineAtOnceExactlyOneWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    claimManually("ledger_seq", ledger.get(0));
                    return "OK";
                } catch (DataAccessException e) {
                    return "REFUSED";
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        Collections.sort(outcomes);

        assertEquals(List.of("OK", "REFUSED"), outcomes);
        assertEquals(1, count("line_claim"));
    }

    @Test
    void theRunClaimCutoffStartsAtZeroAndIsPartOfTheImmutableDefinition() {
        assertEquals(0L, jdbc.queryForObject(
                "SELECT claim_cutoff_seq FROM reconciliation_run WHERE id = ?", Long.class, runId));

        SQLException failure = failure(() -> jdbc.update(
                "UPDATE reconciliation_run SET claim_cutoff_seq = 5 WHERE id = ?", runId));

        assertEquals("23001", failure.getSQLState());
        assertTrue(failure.getMessage().contains("definition of a reconciliation run cannot change"),
                failure.getMessage());
    }

    private long claimByRun(String column, long lineSeq) {
        return jdbc.queryForObject(String.format(RUN_CLAIM, column), Long.class, lineSeq, runId);
    }

    private long claimManually(String column, long lineSeq) {
        return jdbc.queryForObject(String.format(MANUAL_CLAIM, column), Long.class, lineSeq, caseId);
    }

    private long release(String column, long lineSeq, long releasesSeq) {
        return jdbc.queryForObject(String.format(RELEASE, column), Long.class, lineSeq, caseId, releasesSeq);
    }

    private static SQLException failure(Executable action) {
        Throwable t = assertThrows(DataAccessException.class, action);
        while (t != null && !(t instanceof SQLException)) {
            t = t.getCause();
        }
        assertNotNull(t, "expected a database error");
        return (SQLException) t;
    }

    private static int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static RawLine raw(String reference, String amount) {
        return new RawLine("ACC-1", null, "2026-10-09", amount, "GBP", reference, "Acme Ltd", "payment");
    }

    private static IngestionOutcome ingest(LineSource kind, String fileText, RawLine... rawLines) {
        ContentHash hash = ContentHash.ofUtf8(fileText);
        List<AcceptedLine> lines = new ArrayList<>();
        for (int i = 0; i < rawLines.length; i++) {
            lines.add(new AcceptedLine(i + 1, LineNormaliser.normalise(kind, hash, i + 1, rawLines[i])));
        }
        return ingestion.store(new IngestionRequest(kind, hash, FileFormat.CSV, fileText.length(), lines, List.of()));
    }
}