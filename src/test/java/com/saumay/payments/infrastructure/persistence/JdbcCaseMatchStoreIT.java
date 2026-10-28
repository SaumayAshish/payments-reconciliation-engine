package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saumay.payments.application.CaseErrorCode;
import com.saumay.payments.application.CaseIntakeService;
import com.saumay.payments.application.CaseMatchService;
import com.saumay.payments.application.CaseMatchStore.CaseState;
import com.saumay.payments.application.CaseRejectedException;
import com.saumay.payments.application.ReconciliationRunService;
import com.saumay.payments.application.RunHeader;
import com.saumay.payments.application.RunRequest;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.RoutingConfig;
import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.ingestion.AcceptedLine;
import com.saumay.payments.ingestion.FileFormat;
import com.saumay.payments.ingestion.IngestionOutcome;
import com.saumay.payments.ingestion.IngestionRequest;
import com.saumay.payments.ingestion.normalise.LineNormaliser;
import com.saumay.payments.ingestion.normalise.RawLine;
import com.saumay.payments.matching.MatchingConfig;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Manual match, split and unmatch against a real PostgreSQL 17 (FRD-12, ADR-0009). Scenario: a statement with
 * REF-1 10.00 (auto-confirmed against the ledger), ONLY-A 30.00 and ONLY-B 30.00 (each a MISSING_LEDGER case);
 * after the run the ledger receives LATE-1 30.00, LATE-2 10.00 and LATE-3 20.00 for the analysts to match by hand.
 */
@Testcontainers
class JdbcCaseMatchStoreIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 9);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcIngestionStore ingestion;
    private static ReconciliationRunService runService;
    private static CaseIntakeService intake;
    private static CaseMatchService matches;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        ingestion = new JdbcIngestionStore(dataSource);
        runService = new ReconciliationRunService(new JdbcRunStore(dataSource), Clock.systemUTC());
        intake = new CaseIntakeService(new JdbcCaseIntakeStore(dataSource, Clock.systemUTC()), RoutingConfig.defaults());
        matches = new CaseMatchService(new JdbcCaseMatchStore(dataSource), RoutingConfig.defaults(), Clock.systemUTC());
    }

    @BeforeEach
    void emptyTheTables() {
        jdbc.execute("TRUNCATE line_claim, case_attachment, case_comment, case_assignment, case_activity,"
                + " exception_case, run_note, run_carried_forward, run_exception_line, run_exception, run_match_line,"
                + " run_match, reconciliation_run_statement_batch, reconciliation_run, rejected_line,"
                + " statement_line, ledger_entry, ingestion_file RESTART IDENTITY CASCADE");
    }

    @Test
    void aManualMatchResolvesTheCaseAndClaimsBothLines() {
        aScenario();
        long caseA = investigating("ONLY-A");

        CaseState after = matches.resolve(caseA, 2, "alice", "Same payer, bank changed the reference",
                List.of(statementSeq("ONLY-A")), List.of(ledgerSeq("LATE-1")));

        assertEquals(CaseStatus.RESOLVED, after.status());
        assertEquals(3, after.version());
        assertEquals("MANUAL_MATCH", text("SELECT resolution_type FROM exception_case WHERE id = " + caseA));
        assertEquals(2, count("line_claim WHERE case_id = " + caseA + " AND source = 'MANUAL_MATCH'"
                + " AND actor = 'alice' AND action = 'CLAIM'"));
        assertEquals(1, count("case_activity WHERE case_id = " + caseA + " AND activity_type = 'RESOLVED'"));
    }

    @Test
    void aSplitClaimsEveryComponent() {
        aScenario();
        long caseA = investigating("ONLY-A");

        matches.resolve(caseA, 2, "alice", "Paid in two parts",
                List.of(statementSeq("ONLY-A")), List.of(ledgerSeq("LATE-2"), ledgerSeq("LATE-3")));

        assertEquals("SPLIT", text("SELECT resolution_type FROM exception_case WHERE id = " + caseA));
        assertEquals(3, count("line_claim WHERE case_id = " + caseA + " AND source = 'SPLIT'"));
    }

    @Test
    void sidesThatDoNotAddUpAreRejectedAndNothingIsWritten() {
        aScenario();
        long caseA = investigating("ONLY-A");

        assertCode(CaseErrorCode.MATCH_INVALID, () -> matches.resolve(caseA, 2, "alice", "why",
                List.of(statementSeq("ONLY-A")), List.of(ledgerSeq("LATE-2"))));

        assertEquals("INVESTIGATING", text("SELECT status FROM exception_case WHERE id = " + caseA));
        assertEquals(0, count("line_claim WHERE case_id IS NOT NULL"));
    }

    @Test
    void aStaleVersionAndAnUninvestigatedCaseAreRefused() {
        aScenario();
        long caseA = investigating("ONLY-A");
        long caseB = caseFor("ONLY-B");

        assertCode(CaseErrorCode.CASE_VERSION_CONFLICT, () -> matches.resolve(caseA, 1, "alice", "why",
                List.of(statementSeq("ONLY-A")), List.of(ledgerSeq("LATE-1"))));
        assertCode(CaseErrorCode.ILLEGAL_STATE_TRANSITION, () -> matches.resolve(caseB, 0, "alice", "why",
                List.of(statementSeq("ONLY-B")), List.of(ledgerSeq("LATE-1"))));
    }

    @Test
    void aLineAlreadyClaimedByAnotherCaseIsRefusedAndTheSecondCaseIsUntouched() {
        aScenario();
        long caseA = investigating("ONLY-A");
        long caseB = investigating("ONLY-B");
        matches.resolve(caseA, 2, "alice", "first", List.of(statementSeq("ONLY-A")), List.of(ledgerSeq("LATE-1")));

        assertCode(CaseErrorCode.MATCH_LINE_ALREADY_CLAIMED, () -> matches.resolve(caseB, 2, "bob", "second",
                List.of(statementSeq("ONLY-B")), List.of(ledgerSeq("LATE-1"))));

        assertEquals("INVESTIGATING", text("SELECT status FROM exception_case WHERE id = " + caseB));
        assertEquals(0, count("line_claim WHERE case_id = " + caseB));
    }

    @Test
    void twoAnalystsRacingForTheSameLineLetExactlyOneWin() throws Exception {
        aScenario();
        long caseA = investigating("ONLY-A");
        long caseB = investigating("ONLY-B");
        long late = ledgerSeq("LATE-1");
        CountDownLatch go = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> a = pool.submit(() -> race(go, caseA, "ONLY-A", late));
            Future<String> b = pool.submit(() -> race(go, caseB, "ONLY-B", late));
            go.countDown();
            List<String> outcomes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("WON"::equals).count());
            assertEquals(1, outcomes.stream()
                    .filter(CaseErrorCode.MATCH_LINE_ALREADY_CLAIMED.name()::equals).count());
        }

        assertEquals(1, count("exception_case WHERE status = 'RESOLVED'"));
        assertEquals(2, count("line_claim WHERE case_id IS NOT NULL"));
    }

    @Test
    void unmatchingAManualMatchReleasesItsLinesAndOpensALinkedReviewCase() {
        aScenario();
        long caseA = investigating("ONLY-A");
        long caseB = investigating("ONLY-B");
        matches.resolve(caseA, 2, "alice", "first", List.of(statementSeq("ONLY-A")), List.of(ledgerSeq("LATE-1")));

        long reopened = matches.unmatchCase(caseA, "bob", "Wrong payer");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT origin, linked_case_id, kind, case_class, status, amount_minor FROM exception_case WHERE id = ?",
                reopened);
        assertEquals("UNMATCH", row.get("origin"));
        assertEquals(caseA, ((Number) row.get("linked_case_id")).longValue());
        assertEquals("LOW_CONFIDENCE", row.get("kind"));
        assertEquals("REVIEW", row.get("case_class"));
        assertEquals("OPEN", row.get("status"));
        assertEquals(3000, ((Number) row.get("amount_minor")).longValue());
        assertEquals(2, count("line_claim WHERE action = 'RELEASE' AND case_id = " + reopened));
        assertEquals("RESOLVED", text("SELECT status FROM exception_case WHERE id = " + caseA));
        assertEquals(1, count("case_activity WHERE case_id = " + caseA + " AND activity_type = 'MATCH_REJECTED'"));
        // The released line can be matched again by someone else.
        matches.resolve(caseB, 2, "carol", "second", List.of(statementSeq("ONLY-B")), List.of(ledgerSeq("LATE-1")));
        assertEquals("RESOLVED", text("SELECT status FROM exception_case WHERE id = " + caseB));
    }

    @Test
    void unmatchingAnAutoConfirmedRunMatchOpensAnUnlinkedCaseAndFreesTheLines() {
        aScenario();
        long runId = jdbc.queryForObject("SELECT max(id) FROM reconciliation_run", Long.class);
        int ordinal = jdbc.queryForObject(
                "SELECT min(ordinal) FROM run_match WHERE run_id = ? AND band = 'AUTO_CONFIRM'", Integer.class, runId);

        long reopened = matches.unmatchRunMatch(runId, ordinal, "bob", "Not the same invoice");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT origin, linked_case_id, amount_minor FROM exception_case WHERE id = ?", reopened);
        assertEquals("UNMATCH", row.get("origin"));
        assertNull(row.get("linked_case_id"));
        assertEquals(1000, ((Number) row.get("amount_minor")).longValue());
        assertEquals(2, count("line_claim WHERE action = 'RELEASE' AND case_id = " + reopened));
        assertEquals(0, count("line_claim c WHERE c.action = 'CLAIM' AND c.run_id IS NOT NULL AND NOT EXISTS"
                + " (SELECT 1 FROM line_claim r WHERE r.releases_seq = c.seq)"));
    }

    @Test
    void unmatchingTwiceOrUnmatchingAnUnresolvedCaseIsRefused() {
        aScenario();
        long caseA = investigating("ONLY-A");
        long caseB = caseFor("ONLY-B");
        matches.resolve(caseA, 2, "alice", "first", List.of(statementSeq("ONLY-A")), List.of(ledgerSeq("LATE-1")));
        matches.unmatchCase(caseA, "bob", "Wrong payer");

        assertCode(CaseErrorCode.NOTHING_TO_UNMATCH, () -> matches.unmatchCase(caseA, "bob", "again"));
        assertCode(CaseErrorCode.NOTHING_TO_UNMATCH, () -> matches.unmatchCase(caseB, "bob", "never matched"));
    }

    @Test
    void anUnmatchCaseCannotPointAtARunException() {
        aScenario();
        long caseA = caseFor("ONLY-A");

        DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO exception_case (origin, origin_run_id, origin_ordinal, linked_case_id, natural_key, kind,"
                        + " case_class, amount_minor, currency, priority, team, routing_rule, created_business_date,"
                        + " sla_business_days, due_business_date)"
                        + " SELECT 'UNMATCH', origin_run_id, origin_ordinal, id, 'UNMATCH:x', 'LOW_CONFIDENCE',"
                        + " 'REVIEW', 100, 'GBP', 'P3', 'RECON_ANALYSTS', 'r', DATE '2026-10-09', 5,"
                        + " DATE '2026-10-16' FROM exception_case WHERE id = ?", caseA));
        assertEquals("23514", ((SQLException) e.getCause()).getSQLState());
    }

    private static String race(CountDownLatch go, long caseId, String statementRef, long ledgerSeq)
            throws InterruptedException {
        go.await();
        try {
            matches.resolve(caseId, 2, "analyst", "same payer", List.of(statementSeq(statementRef)),
                    List.of(ledgerSeq));
            return "WON";
        } catch (CaseRejectedException e) {
            return e.code().name();
        }
    }

    private static void aScenario() {
        long statements = ingest(LineSource.STATEMENT, "stmt-1",
                raw("REF-1", "10.00"), raw("ONLY-A", "30.00"), raw("ONLY-B", "30.00")).ingestionId();
        ingest(LineSource.LEDGER, "ledger-1", raw("REF-1", "10.00"));
        RunHeader run = runService.start(new RunRequest(BUSINESS_DATE, List.of(statements),
                MatchingConfig.defaults()));
        intake.openCases(run.id());
        ingest(LineSource.LEDGER, "ledger-2", raw("LATE-1", "30.00"), raw("LATE-2", "10.00"), raw("LATE-3", "20.00"));
    }

    private static long caseFor(String statementReference) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT c.id FROM exception_case c JOIN run_exception_line l ON l.run_id = c.origin_run_id"
                        + " AND l.exception_ordinal = c.origin_ordinal AND l.role = 'SUBJECT'"
                        + " JOIN statement_line s ON s.seq = l.statement_seq WHERE s.reference = ?",
                Long.class, statementReference));
    }

    private static long investigating(String statementReference) {
        long caseId = caseFor(statementReference);
        jdbc.update("UPDATE exception_case SET status = 'ASSIGNED', owner = 'alice', version = version + 1"
                + " WHERE id = ?", caseId);
        jdbc.update("UPDATE exception_case SET status = 'INVESTIGATING', version = version + 1 WHERE id = ?", caseId);
        return caseId;
    }

    private static long statementSeq(String reference) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT seq FROM statement_line WHERE reference = ?", Long.class, reference));
    }

    private static long ledgerSeq(String reference) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT seq FROM ledger_entry WHERE reference = ?", Long.class, reference));
    }

    private static void assertCode(CaseErrorCode expected, Runnable action) {
        CaseRejectedException e = assertThrows(CaseRejectedException.class, action::run);
        assertEquals(expected, e.code());
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

    private static String text(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    private static int count(String fromAndWhere) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM " + fromAndWhere, Integer.class));
    }
}