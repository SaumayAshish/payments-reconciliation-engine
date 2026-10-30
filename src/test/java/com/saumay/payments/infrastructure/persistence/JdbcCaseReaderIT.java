package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.application.CaseIntakeService;
import com.saumay.payments.application.CaseMatchService;
import com.saumay.payments.application.CaseReader;
import com.saumay.payments.application.CaseReader.CaseDetail;
import com.saumay.payments.application.CaseReader.CaseFilter;
import com.saumay.payments.application.CaseReader.CasePage;
import com.saumay.payments.application.CaseRecord;
import com.saumay.payments.application.ReconciliationRunService;
import com.saumay.payments.application.RunHeader;
import com.saumay.payments.application.RunRequest;
import com.saumay.payments.domain.cases.CaseActivityType;
import com.saumay.payments.domain.cases.CaseKind;
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
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * The case queue and case view against a real PostgreSQL 17 (FRD-12). Scenario: two MISSING_LEDGER cases (ONLY-A and
 * ONLY-B, 30.00 each) after a run, plus a late ledger line LATE-1 30.00 that an analyst matches by hand.
 */
@Testcontainers
class JdbcCaseReaderIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 9);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcIngestionStore ingestion;
    private static ReconciliationRunService runService;
    private static CaseIntakeService intake;
    private static CaseMatchService matches;
    private static CaseReader reader;

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
        reader = new JdbcCaseReader(dataSource);
    }

    @BeforeEach
    void emptyTheTables() {
        jdbc.execute("TRUNCATE line_claim, case_attachment, case_comment, case_assignment, case_activity,"
                + " exception_case, run_note, run_carried_forward, run_exception_line, run_exception, run_match_line,"
                + " run_match, reconciliation_run_statement_batch, reconciliation_run, rejected_line,"
                + " statement_line, ledger_entry, ingestion_file RESTART IDENTITY CASCADE");
        long statements = ingest(LineSource.STATEMENT, "stmt-1",
                raw("REF-1", "10.00"), raw("ONLY-A", "30.00"), raw("ONLY-B", "30.00")).ingestionId();
        ingest(LineSource.LEDGER, "ledger-1", raw("REF-1", "10.00"));
        RunHeader run = runService.start(new RunRequest(BUSINESS_DATE, List.of(statements),
                MatchingConfig.defaults()));
        intake.openCases(run.id());
        ingest(LineSource.LEDGER, "ledger-2", raw("LATE-1", "30.00"));
    }

    @Test
    void theQueueListsEveryOpenCaseInIdOrder() {
        CasePage page = reader.list(CaseFilter.none(), 0, 10);

        assertEquals(2, page.items().size());
        assertTrue(page.items().get(0).id() < page.items().get(1).id());
        assertNull(page.nextAfterId());
        for (CaseRecord c : page.items()) {
            assertEquals(CaseStatus.OPEN, c.status());
            assertEquals(CaseKind.MISSING_LEDGER, c.kind());
            assertEquals(3000, c.amountMinor());
        }
    }

    @Test
    void filtersNarrowTheQueueByStatusOwnerAndTeam() {
        long caseA = investigating("ONLY-A");

        List<CaseRecord> inProgress = reader.list(
                new CaseFilter(CaseStatus.INVESTIGATING, null, null, null, null), 0, 10).items();
        List<CaseRecord> alices = reader.list(new CaseFilter(null, null, null, null, "alice"), 0, 10).items();
        List<CaseRecord> none = reader.list(new CaseFilter(null, null, null, null, "nobody"), 0, 10).items();
        CaseRecord first = reader.list(CaseFilter.none(), 0, 10).items().get(0);
        List<CaseRecord> sameTeam = reader.list(new CaseFilter(null, first.team(), null, null, null), 0, 10).items();

        assertEquals(List.of(caseA), inProgress.stream().map(CaseRecord::id).toList());
        assertEquals(List.of(caseA), alices.stream().map(CaseRecord::id).toList());
        assertTrue(none.isEmpty());
        assertTrue(sameTeam.stream().allMatch(c -> c.team() == first.team()));
    }

    @Test
    void pagingWalksTheQueueWithoutGapsOrRepeats() {
        CasePage first = reader.list(CaseFilter.none(), 0, 1);
        assertEquals(1, first.items().size());
        assertNotNull(first.nextAfterId());

        CasePage second = reader.list(CaseFilter.none(), first.nextAfterId(), 1);

        assertEquals(1, second.items().size());
        assertTrue(second.items().get(0).id() > first.items().get(0).id());
        assertNull(second.nextAfterId());
    }

    @Test
    void theCaseViewCarriesTimelineCommentsAndClaims() {
        long caseA = investigating("ONLY-A");
        matches.resolve(caseA, 2, "alice", "Same payer, bank changed the reference",
                List.of(seq("statement_line", "ONLY-A")), List.of(seq("ledger_entry", "LATE-1")));
        jdbc.update("INSERT INTO case_comment (case_id, ordinal, author, body, created_at)"
                + " VALUES (?, 1, 'bob', 'Called the bank', now())", caseA);

        CaseDetail view = reader.find(caseA).orElseThrow();

        assertEquals(CaseStatus.RESOLVED, view.record().status());
        assertEquals("RUN", view.origin());
        assertEquals("MANUAL_MATCH", view.resolutionType());
        assertEquals("Same payer, bank changed the reference", view.resolutionReason());
        assertNotNull(view.resolvedAt());
        assertEquals(CaseActivityType.RESOLVED, view.activity().get(view.activity().size() - 1).type());
        for (int i = 1; i < view.activity().size(); i++) {
            assertTrue(view.activity().get(i).ordinal() > view.activity().get(i - 1).ordinal());
        }
        assertEquals(1, view.comments().size());
        assertEquals("bob", view.comments().get(0).author());
        assertTrue(view.assignments().isEmpty());
        assertEquals(2, view.claims().size());
        assertTrue(view.claims().stream().allMatch(c -> c.action().equals("CLAIM")
                && c.source().equals("MANUAL_MATCH") && c.actor().equals("alice")));
    }

    @Test
    void anUnknownCaseHasNoView() {
        assertTrue(reader.find(999_999).isEmpty());
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

    private static long seq(String table, String reference) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT seq FROM " + table + " WHERE reference = ?", Long.class, reference));
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