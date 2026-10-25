package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.application.CaseIntakeService;
import com.saumay.payments.application.IntakeResult;
import com.saumay.payments.application.ReconciliationRunService;
import com.saumay.payments.application.RunHeader;
import com.saumay.payments.application.RunRejectedException;
import com.saumay.payments.application.RunRequest;
import com.saumay.payments.domain.cases.RoutingConfig;
import com.saumay.payments.domain.cases.SlaPolicy;
import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.domain.money.CurrencyCode;
import com.saumay.payments.ingestion.AcceptedLine;
import com.saumay.payments.ingestion.FileFormat;
import com.saumay.payments.ingestion.IngestionRequest;
import com.saumay.payments.ingestion.normalise.LineNormaliser;
import com.saumay.payments.ingestion.normalise.RawLine;
import com.saumay.payments.matching.MatchingConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * FRD-06 case intake against a real PostgreSQL 17: a run executed through the real service becomes routed cases,
 * each with its CREATED timeline entry, and opening cases again changes nothing (AC-06.3).
 */
@Testcontainers
class JdbcCaseIntakeStoreIT {

    // 2026-10-09 is a Friday.
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 9);
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcIngestionStore ingestion;
    private static JdbcRunStore runs;
    private static ReconciliationRunService runService;
    private static JdbcCaseIntakeStore intakeStore;

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
        intakeStore = new JdbcCaseIntakeStore(dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void emptyTheTables() {
        jdbc.execute("TRUNCATE case_attachment, case_comment, case_assignment, case_activity, exception_case,"
                + " run_note, run_carried_forward, run_exception_line, run_exception, run_match_line,"
                + " run_match, reconciliation_run_statement_batch, reconciliation_run, rejected_line,"
                + " statement_line, ledger_entry, ingestion_file RESTART IDENTITY CASCADE");
    }

    @Test
    void everyExceptionBecomesOneRoutedCaseWithItsSlaFixed() {
        RunHeader run = aCompletedRunWithAMissingLedgerEntry();

        IntakeResult result = intake(RoutingConfig.defaults()).openCases(run.id());

        int exceptions = run.summary().exceptions();
        assertEquals(new IntakeResult(run.id(), exceptions, exceptions, 0), result);
        assertEquals(exceptions, count("exception_case"));
        List<CaseRow> missing = casesOfKind("MISSING_LEDGER");
        assertEquals(1, missing.size());
        CaseRow row = missing.get(0);
        assertEquals("BREAK", row.caseClass());
        assertEquals(3000L, row.amountMinor());
        assertEquals("GBP", row.currency());
        assertEquals("OPEN", row.status());
        assertEquals("LEDGER_OPERATIONS", row.team());
        assertEquals("P3", row.priority());
        assertEquals("missing-ledger", row.routingRule());
        assertEquals(BUSINESS_DATE, row.createdBusinessDate());
        assertEquals(5, row.slaBusinessDays());
        assertEquals(LocalDate.of(2026, 10, 16), row.dueBusinessDate());
        assertEquals(run.id(), row.originRunId());
        assertEquals(0L, row.version());
    }

    @Test
    void aLowerThresholdSendsTheSameLineToASeniorAnalystAtP1() {
        RunHeader run = aCompletedRunWithAMissingLedgerEntry();
        RoutingConfig lowLimit = new RoutingConfig(Map.of(GBP, 2000L), 1_000_000L, SlaPolicy.standard());

        intake(lowLimit).openCases(run.id());

        CaseRow row = casesOfKind("MISSING_LEDGER").get(0);
        assertEquals("SENIOR_ANALYST", row.team());
        assertEquals("P1", row.priority());
        assertEquals("high-value", row.routingRule());
        assertEquals(1, row.slaBusinessDays());
        assertEquals(LocalDate.of(2026, 10, 12), row.dueBusinessDate());
    }

    @Test
    void openingCasesAgainCreatesNothingAndChangesNothing() {
        RunHeader run = aCompletedRunWithAMissingLedgerEntry();
        CaseIntakeService service = intake(RoutingConfig.defaults());
        service.openCases(run.id());
        int casesBefore = count("exception_case");
        int activityBefore = count("case_activity");

        IntakeResult again = service.openCases(run.id());

        assertEquals(0, again.created());
        assertEquals(run.summary().exceptions(), again.alreadyOpen());
        assertEquals(casesBefore, count("exception_case"));
        assertEquals(activityBefore, count("case_activity"));
    }

    @Test
    void everyCaseHasExactlyOneCreatedEntryOnItsTimeline() {
        RunHeader run = aCompletedRunWithAMissingLedgerEntry();
        intake(RoutingConfig.defaults()).openCases(run.id());

        assertEquals(count("exception_case"), count("case_activity"));
        assertEquals(count("exception_case"), count("(SELECT DISTINCT case_id FROM case_activity) d"));
        List<Instant> times = jdbc.query(
                "SELECT occurred_at FROM case_activity WHERE ordinal = 1 AND activity_type = 'CREATED'"
                        + " AND actor = 'system'",
                (rs, n) -> rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
        assertEquals(count("case_activity"), times.size());
        assertTrue(times.stream().allMatch(NOW::equals));
    }

    @Test
    void aRunThatHasNotCompletedOpensNoCases() {
        long statements = ingest(LineSource.STATEMENT, "stmt-1", raw("REF-1", "10.00", "2026-10-09")).ingestionId();
        RunHeader created = runs.create(new RunRequest(BUSINESS_DATE, List.of(statements), MatchingConfig.defaults()));

        assertThrows(RunRejectedException.class, () -> intake(RoutingConfig.defaults()).openCases(created.id()));

        assertEquals(0, count("exception_case"));
    }

    @Test
    void anUnknownRunIsRejected() {
        assertThrows(RunRejectedException.class, () -> intake(RoutingConfig.defaults()).openCases(404));
    }

    private static CaseIntakeService intake(RoutingConfig config) {
        return new CaseIntakeService(intakeStore, config);
    }

    private RunHeader aCompletedRunWithAMissingLedgerEntry() {
        long statements = ingest(LineSource.STATEMENT, "stmt-1",
                raw("REF-1", "10.00", "2026-10-09"),
                raw("REF-2", "20.00", "2026-10-09"),
                raw("ONLY-S", "30.00", "2026-10-09")).ingestionId();
        ingest(LineSource.LEDGER, "ledger-1",
                raw("REF-1", "10.00", "2026-10-09"),
                raw("REF-2", "20.00", "2026-10-14"),
                raw("ONLY-L", "40.00", "2026-10-08"));
        RunHeader run = runService.start(new RunRequest(BUSINESS_DATE, List.of(statements), MatchingConfig.defaults()));
        assertTrue(run.summary().exceptions() > 0, "the scenario must raise at least one exception");
        return run;
    }

    private static RawLine raw(String reference, String amount, String valueDate) {
        return new RawLine("ACC-1", null, valueDate, amount, "GBP", reference, "Acme Ltd", "payment");
    }

    private static com.saumay.payments.ingestion.IngestionOutcome ingest(LineSource kind, String fileText,
            RawLine... rawLines) {
        ContentHash hash = ContentHash.ofUtf8(fileText);
        List<AcceptedLine> lines = new ArrayList<>();
        for (int i = 0; i < rawLines.length; i++) {
            lines.add(new AcceptedLine(i + 1, LineNormaliser.normalise(kind, hash, i + 1, rawLines[i])));
        }
        return ingestion.store(new IngestionRequest(kind, hash, FileFormat.CSV, fileText.length(), lines, List.of()));
    }

    private static List<CaseRow> casesOfKind(String kind) {
        return jdbc.query("SELECT kind, case_class, amount_minor, currency, status, priority, team, routing_rule,"
                        + " created_business_date, sla_business_days, due_business_date, origin_run_id, version"
                        + " FROM exception_case WHERE kind = ? ORDER BY id",
                (rs, n) -> new CaseRow(rs.getString("kind"), rs.getString("case_class"), rs.getLong("amount_minor"),
                        rs.getString("currency").trim(), rs.getString("status"), rs.getString("priority"),
                        rs.getString("team"), rs.getString("routing_rule"),
                        rs.getObject("created_business_date", LocalDate.class), rs.getInt("sla_business_days"),
                        rs.getObject("due_business_date", LocalDate.class), rs.getLong("origin_run_id"),
                        rs.getLong("version")), kind);
    }

    private static int count(String table) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
    }

    private record CaseRow(String kind, String caseClass, long amountMinor, String currency, String status,
            String priority, String team, String routingRule, LocalDate createdBusinessDate, int slaBusinessDays,
            LocalDate dueBusinessDate, long originRunId, long version) {
    }
}