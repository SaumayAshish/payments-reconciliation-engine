package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.saumay.payments.application.CaseIntakeService;
import com.saumay.payments.application.ReconciliationRunService;
import com.saumay.payments.application.RunHeader;
import com.saumay.payments.application.RunInputs;
import com.saumay.payments.application.RunRequest;
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
import com.saumay.payments.matching.ReconciliationRun;
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
 * ADR-0009 end to end against a real PostgreSQL 17: a run claims the lines of its auto-confirmed matches, a later run
 * leaves them out, a replay still sees the original inputs, and released lines become available again.
 */
@Testcontainers
class JdbcRunClaimsIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 9);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcIngestionStore ingestion;
    private static JdbcRunStore runs;
    private static ReconciliationRunService runService;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        ingestion = new JdbcIngestionStore(dataSource);
        runs = new JdbcRunStore(dataSource);
        runService = new ReconciliationRunService(runs, Clock.systemUTC());
    }

    @BeforeEach
    void emptyTheTables() {
        jdbc.execute("TRUNCATE line_claim, case_attachment, case_comment, case_assignment, case_activity,"
                + " exception_case, run_note, run_carried_forward, run_exception_line, run_exception, run_match_line,"
                + " run_match, reconciliation_run_statement_batch, reconciliation_run, rejected_line,"
                + " statement_line, ledger_entry, ingestion_file RESTART IDENTITY CASCADE");
    }

    @Test
    void aRunClaimsOnlyTheLinesOfItsAutoConfirmedMatches() {
        long statements = aScenario();

        RunHeader first = start(statements);

        assertEquals(2, first.summary().autoConfirmedMatches());
        assertEquals(4, count("line_claim"));
        assertEquals(0, count("line_claim c WHERE c.source <> 'RUN_MATCH' OR c.action <> 'CLAIM'"));
        assertEquals(0, count("line_claim c WHERE NOT EXISTS (SELECT 1 FROM run_match m"
                + " WHERE m.run_id = c.run_id AND m.ordinal = c.run_match_ordinal AND m.band = 'AUTO_CONFIRM')"));
    }

    @Test
    void aLaterRunDoesNotRematchLinesAnEarlierRunConfirmed() {
        long statements = aScenario();
        RunHeader first = start(statements);
        assertEquals(3, first.summary().statementLines());
        assertEquals(3, first.summary().ledgerEntries());

        RunHeader second = start(statements);

        assertEquals(1, second.summary().statementLines());
        assertEquals(1, second.summary().ledgerEntries());
        assertEquals(0, second.summary().matches());
        assertEquals(4, count("line_claim"));
    }

    @Test
    void theFirstRunStillSeesItsOriginalInputsAndReproducesItsHashAfterALaterRun() {
        long statements = aScenario();
        RunHeader first = start(statements);
        start(statements);

        RunInputs inputs = runs.loadInputs(first.id());

        assertEquals(3, inputs.statements().size());
        assertEquals(3, inputs.ledger().size());
        String recomputed = ReconciliationRun.compute(inputs.statements(), inputs.ledger(), inputs.businessDate(),
                inputs.config()).outputHash().hex();
        assertEquals(first.outputHash(), recomputed);
    }

    @Test
    void linesBecomeAvailableAgainOnceTheirClaimsAreReleased() {
        long statements = aScenario();
        RunHeader first = start(statements);
        new CaseIntakeService(new JdbcCaseIntakeStore(dataSource, Clock.systemUTC()), RoutingConfig.defaults())
                .openCases(first.id());
        long caseId = jdbc.queryForObject("SELECT min(id) FROM exception_case", Long.class);
        jdbc.update("INSERT INTO line_claim (action, statement_seq, ledger_seq, source, case_id, actor, releases_seq)"
                + " SELECT 'RELEASE', statement_seq, ledger_seq, 'UNMATCH', ?, 'bob', seq"
                + " FROM line_claim WHERE action = 'CLAIM'", caseId);

        RunHeader third = start(statements);

        assertEquals(3, third.summary().statementLines());
        assertEquals(3, third.summary().ledgerEntries());
        assertEquals(2, third.summary().autoConfirmedMatches());
        assertEquals(12, count("line_claim"));
    }

    @Test
    void aBulkClaimOfTwentyThousandLinesInOneTransactionIsAccepted() {
        int lines = 20_000;
        List<RawLine> raws = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            raws.add(raw("BULK-" + i, "1.00"));
        }
        ingest(LineSource.LEDGER, "ledger-bulk", raws.toArray(RawLine[]::new));
        long runId = jdbc.queryForObject(
                "INSERT INTO reconciliation_run (business_date, status, ledger_cutoff_seq, auto_threshold_milli,"
                        + " review_threshold_milli, date_window_business_days, lookback_business_days,"
                        + " max_one_to_many_candidates, fuzzy_min_similarity_milli, fuzzy_counterparty_weight_milli)"
                        + " VALUES (DATE '2026-10-09', 'CREATED', 0, 950, 700, 3, 10, 8, 600, 600) RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO run_match (run_id, ordinal, tier, score_milli, band, reason)"
                + " VALUES (?, 1, 'EXACT', 1000, 'AUTO_CONFIRM', 'r')", runId);

        jdbc.update("INSERT INTO line_claim (action, ledger_seq, source, run_id, run_match_ordinal)"
                + " SELECT 'CLAIM', seq, 'RUN_MATCH', ?, 1 FROM ledger_entry", runId);

        assertEquals(lines, count("line_claim"));
    }

    private long aScenario() {
        long statements = ingest(LineSource.STATEMENT, "stmt-1",
                raw("REF-1", "10.00"), raw("REF-2", "20.00"), raw("ONLY-S", "30.00")).ingestionId();
        ingest(LineSource.LEDGER, "ledger-1",
                new RawLine("ACC-1", null, "2026-10-09", "10.00", "GBP", "REF-1", "Acme Ltd", "payment"),
                new RawLine("ACC-1", null, "2026-10-09", "20.00", "GBP", "REF-2", "Acme Ltd", "payment"),
                new RawLine("ACC-1", null, "2026-10-08", "40.00", "GBP", "ONLY-L", "Acme Ltd", "payment"));
        return statements;
    }

    private static RunHeader start(long statementIngestionId) {
        return runService.start(new RunRequest(BUSINESS_DATE, List.of(statementIngestionId),
                MatchingConfig.defaults()));
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

    private static int count(String fromAndWhere) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM " + fromAndWhere, Integer.class));
    }
}