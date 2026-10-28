package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.PaymentsApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * FRD-10 over HTTP, end to end: files are uploaded through the ingestion API, a run is started, read back, paged
 * and replayed, and every refusal comes back as the one error shape with a stable code.
 */
class ReconciliationApiIT {

    private static final String HEADER =
            "account,booking_date,value_date,amount,currency,reference,counterparty,narrative";
    private static final String STATEMENT = String.join("\n", HEADER,
            "ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd,payment",
            "ACC-1,,2026-10-09,20.00,GBP,REF-2,Acme Ltd,payment",
            "ACC-1,,2026-10-09,30.00,GBP,ONLY-S,Acme Ltd,payment");
    private static final String LEDGER = String.join("\n", HEADER,
            "ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd,payment",
            "ACC-1,,2026-10-09,20.00,GBP,REF-2,Acme Ltd,payment",
            "ACC-1,,2026-10-08,40.00,GBP,ONLY-L,Acme Ltd,payment");
    private static final String RUNS = "/api/v1/reconciliations/runs";

    private static PostgreSQLContainer postgres;
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static HttpClient http;
    private static String base;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();
        context = SpringApplication.run(PaymentsApplication.class,
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword());
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        http = HttpClient.newHttpClient();
        base = "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE run_note, run_carried_forward, run_exception_line, run_exception, run_match_line,"
                + " run_match, reconciliation_run_statement_batch, reconciliation_run, rejected_line,"
                + " statement_line, ledger_entry, ingestion_file RESTART IDENTITY CASCADE");
    }

    @Test
    void aRunOverUploadedFilesCompletesWithAHashAndASummary() throws Exception {
        long statements = upload("/api/v1/ingestions/statements", STATEMENT);
        upload("/api/v1/ingestions/ledger", LEDGER);

        HttpResponse<String> response = post(RUNS, runBody(statements));

        assertEquals(201, response.statusCode(), response.body());
        String runId = field(response.body(), "id");
        assertTrue(response.headers().firstValue("Location").orElseThrow().endsWith(RUNS + "/" + runId));
        assertEquals("COMPLETED", field(response.body(), "status"));
        assertEquals(64, field(response.body(), "outputHash").length());
        assertEquals("3", field(response.body(), "statementLines"));
        assertEquals("3", field(response.body(), "ledgerEntries"));
        assertEquals("2", field(response.body(), "matches"));
        assertEquals("2", field(response.body(), "autoConfirmedMatches"));
        assertEquals("950", field(response.body(), "autoThresholdMilli"));
    }

    @Test
    void aStoredRunCanBeReadBack() throws Exception {
        long statements = upload("/api/v1/ingestions/statements", STATEMENT);
        upload("/api/v1/ingestions/ledger", LEDGER);
        String started = post(RUNS, runBody(statements)).body();
        String runId = field(started, "id");

        HttpResponse<String> read = get(RUNS + "/" + runId);

        assertEquals(200, read.statusCode());
        assertEquals(field(started, "outputHash"), field(read.body(), "outputHash"));
        assertEquals("2026-10-09", field(read.body(), "businessDate"));
    }

    @Test
    void theMatchesOfARunAreReturnedWithBothSidesOfEachMatch() throws Exception {
        String runId = aCompletedRun();

        HttpResponse<String> page = get(RUNS + "/" + runId + "/results?view=matches");

        assertEquals(200, page.statusCode());
        assertEquals(2, occurrences(page.body(), "\"ordinal\":"));
        assertEquals(2, occurrences(page.body(), "\"source\":\"STATEMENT\""));
        assertEquals(2, occurrences(page.body(), "\"source\":\"LEDGER\""));
        assertEquals(2, occurrences(page.body(), "\"tier\":\"EXACT\""));
        assertEquals("null", field(page.body(), "nextAfter"));
    }

    @Test
    void pagingByCursorVisitsEveryMatchOnce() throws Exception {
        String runId = aCompletedRun();
        int seen = 0;
        String after = "0";
        int pages = 0;
        do {
            HttpResponse<String> page = get(RUNS + "/" + runId + "/results?view=matches&limit=1&after=" + after);
            assertEquals(200, page.statusCode());
            seen += occurrences(page.body(), "\"ordinal\":");
            after = field(page.body(), "nextAfter");
            pages++;
            assertTrue(pages < 10, "paging must terminate");
        } while (!after.equals("null"));

        assertEquals(2, seen);
    }

    @Test
    void anUnmatchedStatementLineIsReportedAsAnException() throws Exception {
        String runId = aCompletedRun();

        HttpResponse<String> page = get(RUNS + "/" + runId + "/results?view=exceptions");

        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("\"reference\":\"ONLY-S\""), page.body());
    }

    @Test
    void theCarriedForwardAndNotesViewsAreAvailable() throws Exception {
        String runId = aCompletedRun();

        HttpResponse<String> carried = get(RUNS + "/" + runId + "/results?view=carried-forward");
        HttpResponse<String> notes = get(RUNS + "/" + runId + "/results?view=notes");

        assertEquals(200, carried.statusCode());
        assertEquals(200, notes.statusCode());
        assertTrue(notes.body().contains("\"items\":[]"), notes.body());
    }

    @Test
    void replayingARunGivesTheStoredHash() throws Exception {
        long statements = upload("/api/v1/ingestions/statements", STATEMENT);
        upload("/api/v1/ingestions/ledger", LEDGER);
        String started = post(RUNS, runBody(statements)).body();

        HttpResponse<String> replay = post(RUNS + "/" + field(started, "id") + "/replay", "");

        assertEquals(200, replay.statusCode());
        assertEquals("true", field(replay.body(), "identical"));
        assertEquals(field(started, "outputHash"), field(replay.body(), "storedHash"));
        assertEquals(field(started, "outputHash"), field(replay.body(), "replayedHash"));
    }

    @Test
    void aLaterRunSeesOnlyUnclaimedLinesAndEveryRunStillReplaysIdentically() throws Exception {
        long statements = upload("/api/v1/ingestions/statements", STATEMENT);
        upload("/api/v1/ingestions/ledger", LEDGER);

        String first = post(RUNS, runBody(statements)).body();
        String second = post(RUNS, runBody(statements)).body();

        assertNotEquals(field(first, "id"), field(second, "id"));
        // The first run claimed its auto-confirmed lines (ADR-0009), so the second run works on fewer inputs.
        assertNotEquals(field(first, "outputHash"), field(second, "outputHash"));
        assertEquals(2, count("reconciliation_run"));
        // Each run's own inputs are fixed by its cut-offs, so both replay to exactly their stored hash,
        // the first one even though the second run happened after it.
        for (String run : new String[] {first, second}) {
            HttpResponse<String> replay = post(RUNS + "/" + field(run, "id") + "/replay", "");
            assertEquals(200, replay.statusCode());
            assertEquals("true", field(replay.body(), "identical"));
            assertEquals(field(run, "outputHash"), field(replay.body(), "replayedHash"));
        }
    }

    @Test
    void aConfigurationOverrideIsUsedAndEchoedBack() throws Exception {
        long statements = upload("/api/v1/ingestions/statements", STATEMENT);
        upload("/api/v1/ingestions/ledger", LEDGER);
        String body = "{\"businessDate\":\"2026-10-09\",\"statementIngestionIds\":[" + statements + "],"
                + "\"config\":{\"autoThresholdMilli\":990,\"reviewThresholdMilli\":800}}";

        HttpResponse<String> response = post(RUNS, body);

        assertEquals(201, response.statusCode(), response.body());
        assertEquals("990", field(response.body(), "autoThresholdMilli"));
        assertEquals("800", field(response.body(), "reviewThresholdMilli"));
        assertEquals("3", field(response.body(), "dateWindowBusinessDays"));
    }

    @Test
    void anUnknownRunIsNotFound() throws Exception {
        for (HttpResponse<String> response : List.of(get(RUNS + "/999"), get(RUNS + "/999/results"),
                post(RUNS + "/999/replay", ""))) {
            assertEquals(404, response.statusCode());
            assertEquals("RUN_NOT_FOUND", field(response.body(), "code"));
            assertFalse(field(response.body(), "correlationId").isBlank());
        }
    }

    @Test
    void aRunThatNeverCompletedHasNoResultsAndCannotBeReplayed() throws Exception {
        long runId = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO reconciliation_run(business_date, status, ledger_cutoff_seq, auto_threshold_milli,"
                        + " review_threshold_milli, date_window_business_days, lookback_business_days,"
                        + " max_one_to_many_candidates, fuzzy_min_similarity_milli,"
                        + " fuzzy_counterparty_weight_milli)"
                        + " VALUES ('2026-10-09', 'CREATED', 0, 950, 700, 3, 10, 8, 600, 600) RETURNING id",
                Long.class));

        HttpResponse<String> results = get(RUNS + "/" + runId + "/results");
        HttpResponse<String> replay = post(RUNS + "/" + runId + "/replay", "");

        assertEquals(409, results.statusCode());
        assertEquals("RUN_NOT_COMPLETED", field(results.body(), "code"));
        assertEquals(409, replay.statusCode());
        assertEquals("RUN_NOT_COMPLETED", field(replay.body(), "code"));
    }

    @Test
    void aRunNamingAnUnknownIngestionIsRefusedAndStoresNothing() throws Exception {
        HttpResponse<String> response = post(RUNS, runBody(999));

        assertEquals(422, response.statusCode());
        assertEquals("RUN_UNKNOWN_INGESTION", field(response.body(), "code"));
        assertEquals(0, count("reconciliation_run"));
    }

    @Test
    void aRunNamingALedgerIngestionIsRefused() throws Exception {
        long ledger = upload("/api/v1/ingestions/ledger", LEDGER);

        HttpResponse<String> response = post(RUNS, runBody(ledger));

        assertEquals(422, response.statusCode());
        assertEquals("RUN_NOT_A_STATEMENT_INGESTION", field(response.body(), "code"));
        assertEquals(0, count("reconciliation_run"));
    }

    @Test
    void invalidRequestsAreRefusedWithAClearCode() throws Exception {
        assertRefused(post(RUNS, "{\"statementIngestionIds\":[1]}"), 400, "RUN_REQUEST_INVALID");
        assertRefused(post(RUNS, "{\"businessDate\":\"2026-10-09\",\"statementIngestionIds\":[]}"), 400,
                "RUN_REQUEST_INVALID");
        assertRefused(post(RUNS, "{\"businessDate\":\"2026-10-09\"}"), 400, "RUN_REQUEST_INVALID");
        assertRefused(post(RUNS, "{\"businessDate\":\"2026-10-09\",\"statementIngestionIds\":[1],"
                + "\"config\":{\"autoThresholdMilli\":600,\"reviewThresholdMilli\":700}}"), 400,
                "RUN_REQUEST_INVALID");
        assertRefused(post(RUNS, "{not json"), 400, "MALFORMED_REQUEST");
        assertEquals(0, count("reconciliation_run"));
    }

    @Test
    void invalidResultParametersAreRefused() throws Exception {
        String runId = aCompletedRun();

        assertRefused(get(RUNS + "/" + runId + "/results?view=everything"), 400, "INVALID_RESULT_VIEW");
        assertRefused(get(RUNS + "/" + runId + "/results?limit=501"), 400, "INVALID_PAGE_PARAMETER");
        assertRefused(get(RUNS + "/" + runId + "/results?limit=0"), 400, "INVALID_PAGE_PARAMETER");
        assertRefused(get(RUNS + "/" + runId + "/results?after=-1"), 400, "INVALID_PAGE_PARAMETER");
    }

    private static void assertRefused(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, field(response.body(), "code"));
        assertFalse(response.body().contains("Exception"), "no internals may leak: " + response.body());
    }

    private String aCompletedRun() throws Exception {
        long statements = upload("/api/v1/ingestions/statements", STATEMENT);
        upload("/api/v1/ingestions/ledger", LEDGER);
        return field(post(RUNS, runBody(statements)).body(), "id");
    }

    private static String runBody(long statementIngestionId) {
        return "{\"businessDate\":\"2026-10-09\",\"statementIngestionIds\":[" + statementIngestionId + "]}";
    }

    private static long upload(String path, String csv) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "text/csv")
                .POST(BodyPublishers.ofString(csv))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        return Long.parseLong(field(response.body(), "ingestionId"));
    }

    private static HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The first value of a JSON property, unquoted: enough for the flat, compact bodies this API returns. */
    private static String field(String body, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\":(\"[^\"]*\"|-?[0-9]+|null|true|false)")
                .matcher(body);
        assertTrue(m.find(), "no property " + name + " in " + body);
        return m.group(1).replace("\"", "");
    }

    private static int occurrences(String body, String needle) {
        int count = 0;
        for (int at = body.indexOf(needle); at >= 0; at = body.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static int count(String table) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
    }
}