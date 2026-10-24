package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.application.ReconciliationRunService;
import com.saumay.payments.application.RunHeader;
import com.saumay.payments.application.RunRequest;
import com.saumay.payments.application.RunResultReader.ExceptionView;
import com.saumay.payments.application.RunResultReader.LineView;
import com.saumay.payments.application.RunResultReader.MatchView;
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
 * FRD-10 read side against a real PostgreSQL 17: a run executed through the real service is read back complete,
 * line by line, and paged by cursor without skipping or repeating a row.
 */
@Testcontainers
class JdbcRunResultReaderIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 9);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static JdbcTemplate jdbc;
    private static JdbcIngestionStore ingestion;
    private static JdbcRunStore runs;
    private static JdbcRunResultReader reader;
    private static ReconciliationRunService service;

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
        reader = new JdbcRunResultReader(dataSource);
        service = new ReconciliationRunService(runs, Clock.systemUTC());
    }

    @BeforeEach
    void emptyTheTables() {
        jdbc.execute("TRUNCATE run_note, run_carried_forward, run_exception_line, run_exception, run_match_line,"
                + " run_match, reconciliation_run_statement_batch, reconciliation_run, rejected_line,"
                + " statement_line, ledger_entry, ingestion_file RESTART IDENTITY CASCADE");
    }

    @Test
    void everyStoredMatchComesBackWithBothSidesAndItsScore() {
        RunHeader run = aRunWithMatchesExceptionsAndACarriedEntry();

        List<MatchView> matches = reader.matches(run.id(), 0, 100);

        assertEquals(run.summary().matches(), matches.size());
        assertEquals(count("run_match_line"), matches.stream().mapToInt(m -> m.lines().size()).sum());
        for (MatchView match : matches) {
            assertTrue(match.lines().stream().anyMatch(l -> l.source() == LineSource.STATEMENT), match.reason());
            assertTrue(match.lines().stream().anyMatch(l -> l.source() == LineSource.LEDGER), match.reason());
            assertTrue(match.scoreMilli() >= 700 && match.scoreMilli() <= 1000);
        }
    }

    @Test
    void aLineViewCarriesTheStoredReferenceAmountCurrencyAndDate() {
        RunHeader run = aRunWithMatchesExceptionsAndACarriedEntry();

        List<LineView> lines = reader.matches(run.id(), 0, 100).stream().flatMap(m -> m.lines().stream()).toList();

        assertTrue(lines.stream().anyMatch(l -> l.source() == LineSource.STATEMENT && l.reference().equals("REF-1")
                && l.amountMinor() == 1000 && l.currency().equals("GBP") && l.valueDate().equals(BUSINESS_DATE)));
    }

    @Test
    void pagingByCursorVisitsEveryMatchExactlyOnceInOrder() {
        RunHeader run = aRunWithMatchesExceptionsAndACarriedEntry();
        List<Integer> all = reader.matches(run.id(), 0, 100).stream().map(MatchView::ordinal).toList();

        List<Integer> paged = new ArrayList<>();
        int cursor = 0;
        while (true) {
            List<MatchView> page = reader.matches(run.id(), cursor, 1);
            if (page.isEmpty()) {
                break;
            }
            assertEquals(1, page.size());
            paged.add(page.get(0).ordinal());
            cursor = page.get(0).ordinal();
        }

        assertEquals(all, paged);
        assertEquals(paged.stream().sorted().toList(), paged);
    }

    @Test
    void aPageCountsMatchesNotLines() {
        RunHeader run = aRunWithMatchesExceptionsAndACarriedEntry();

        List<MatchView> onePage = reader.matches(run.id(), 0, 1);

        assertEquals(1, onePage.size());
        assertTrue(onePage.get(0).lines().size() >= 2);
    }

    @Test
    void everyStoredExceptionComesBackWithItsSubjectAndCandidateLines() {
        RunHeader run = aRunWithMatchesExceptionsAndACarriedEntry();

        List<ExceptionView> exceptions = reader.exceptions(run.id(), 0, 100);

        assertEquals(run.summary().exceptions(), exceptions.size());
        assertEquals(count("run_exception_line"),
                exceptions.stream().mapToInt(e -> e.subjects().size() + e.candidates().size()).sum());
        for (ExceptionView exception : exceptions) {
            assertFalse(exception.subjects().isEmpty(), exception.kind());
            assertFalse(exception.kind().isBlank());
        }
    }

    @Test
    void carriedForwardEntriesComeBackAscendingAndPageByCursor() {
        RunHeader run = aRunWithMatchesExceptionsAndACarriedEntry();

        List<Long> carried = reader.carriedForward(run.id(), 0, 100);

        assertEquals(run.summary().carriedForward(), carried.size());
        assertEquals(carried.stream().sorted().toList(), carried);
        assertTrue(reader.carriedForward(run.id(), carried.get(carried.size() - 1), 100).isEmpty());
    }

    @Test
    void aRunWithNothingStoredReadsAsEmptyNotAsAnError() {
        assertTrue(reader.matches(404, 0, 10).isEmpty());
        assertTrue(reader.exceptions(404, 0, 10).isEmpty());
        assertTrue(reader.carriedForward(404, 0, 10).isEmpty());
        assertTrue(reader.notes(404).isEmpty());
    }

    private RunHeader aRunWithMatchesExceptionsAndACarriedEntry() {
        long statements = ingest(LineSource.STATEMENT, "stmt-1",
                raw("REF-1", "10.00", "2026-10-09"),
                raw("REF-2", "20.00", "2026-10-09"),
                raw("ONLY-S", "30.00", "2026-10-09")).ingestionId();
        ingest(LineSource.LEDGER, "ledger-1",
                raw("REF-1", "10.00", "2026-10-09"),
                raw("REF-2", "20.00", "2026-10-14"),
                raw("ONLY-L", "40.00", "2026-10-08"));
        RunHeader run = service.start(new RunRequest(BUSINESS_DATE, List.of(statements), MatchingConfig.defaults()));
        assertTrue(run.summary().matches() > 0 && run.summary().exceptions() > 0 && run.summary().carriedForward() > 0,
                "the scenario must exercise matches, exceptions and a carried-forward entry");
        return run;
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

    private static int count(String table) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
    }
}