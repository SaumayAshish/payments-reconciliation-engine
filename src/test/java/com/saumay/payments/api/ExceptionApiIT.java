package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.PaymentsApplication;
import com.saumay.payments.application.CaseIntakeService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.ArrayList;
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
 * The exception endpoints over real HTTP against the real application and PostgreSQL 17 (FRD-12). Each test starts
 * from the same story: a statement with REF-1 (auto-confirmed) and two lines with no ledger entry (ONLY-A and ONLY-B,
 * 30.00 each, one case each), then a late ledger file with LATE-1 30.00 and LATE-2 10.00 for analysts to match.
 */
class ExceptionApiIT {

    private static final String HEADER =
            "account,booking_date,value_date,amount,currency,reference,counterparty,narrative";
    private static final String STATEMENT = String.join("\n", HEADER,
            "ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd,payment",
            "ACC-1,,2026-10-09,30.00,GBP,ONLY-A,Acme Ltd,payment",
            "ACC-1,,2026-10-09,30.00,GBP,ONLY-B,Acme Ltd,payment");
    private static final String LEDGER = String.join("\n", HEADER,
            "ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd,payment");
    private static final String LATE = String.join("\n", HEADER,
            "ACC-1,,2026-10-09,30.00,GBP,LATE-1,Acme Ltd,payment",
            "ACC-1,,2026-10-09,10.00,GBP,LATE-2,Acme Ltd,payment");
    private static final String CASES = "/api/v1/exceptions";

    private static PostgreSQLContainer postgres;
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static HttpClient http;
    private static String base;
    private static long runId;

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
    void theStory() throws Exception {
        jdbc.execute("TRUNCATE line_claim, case_attachment, case_comment, case_assignment, case_activity,"
                + " exception_case, run_note, run_carried_forward, run_exception_line, run_exception, run_match_line,"
                + " run_match, reconciliation_run_statement_batch, reconciliation_run, rejected_line,"
                + " statement_line, ledger_entry, ingestion_file RESTART IDENTITY CASCADE");
        long statements = number(call("POST", "/api/v1/ingestions/statements", null, "text/csv", STATEMENT).body(),
                "ingestionId");
        call("POST", "/api/v1/ingestions/ledger", null, "text/csv", LEDGER);
        HttpResponse<String> run = call("POST", "/api/v1/reconciliations/runs", null, "application/json",
                "{\"businessDate\":\"2026-10-09\",\"statementIngestionIds\":[" + statements + "]}");
        assertEquals(201, run.statusCode());
        runId = number(run.body(), "id");
        context.getBean(CaseIntakeService.class).openCases(runId);
        call("POST", "/api/v1/ingestions/ledger", null, "text/csv", LATE);
    }

    @Test
    void theQueueListsEveryCaseAndFiltersByStatus() throws Exception {
        HttpResponse<String> all = get(CASES);

        assertEquals(200, all.statusCode());
        assertEquals(2, ids(all.body()).size());
        assertEquals(2, ids(get(CASES + "?status=open").body()).size());
        assertEquals(0, ids(get(CASES + "?status=RESOLVED").body()).size());
        HttpResponse<String> firstPage = get(CASES + "?limit=1");
        assertEquals(1, ids(firstPage.body()).size());
        assertTrue(!firstPage.body().contains("\"nextAfter\":null"), "a full page points at the next one");
    }

    @Test
    void badQueryParametersAreRefusedWithTheirOwnCodes() throws Exception {
        assertError(get(CASES + "?status=NOPE"), 400, "INVALID_FILTER");
        assertError(get(CASES + "?limit=0"), 400, "INVALID_PAGE_PARAMETER");
        assertError(get(CASES + "?after=-1"), 400, "INVALID_PAGE_PARAMETER");
    }

    @Test
    void writesNeedAnActor() throws Exception {
        long id = caseFor("ONLY-A");

        assertError(act(CASES + "/" + id + "/assign", null, "{\"expectedVersion\":0,\"owner\":\"alice\"}"),
                400, "ACTOR_REQUIRED");
        assertError(act(CASES + "/" + id + "/assign", "  ", "{\"expectedVersion\":0,\"owner\":\"alice\"}"),
                400, "ACTOR_REQUIRED");
    }

    @Test
    void aCaseWalksFromOpenToResolvedAndItsViewTellsTheStory() throws Exception {
        long id = investigating("ONLY-A");

        HttpResponse<String> match = act(CASES + "/" + id + "/match", "alice",
                matchBody(2, "Same payer, bank changed the reference", "ONLY-A", "LATE-1"));

        assertEquals(200, match.statusCode());
        assertTrue(match.body().contains("\"status\":\"RESOLVED\""));
        String view = get(CASES + "/" + id).body();
        assertTrue(view.contains("\"resolutionType\":\"MANUAL_MATCH\""));
        assertTrue(view.contains("\"type\":\"RESOLVED\""));
        assertTrue(view.contains("\"source\":\"MANUAL_MATCH\""));
        assertEquals(1, ids(get(CASES + "?status=RESOLVED").body()).size());
    }

    @Test
    void aCaseCanBeEscalatedFromInvestigation() throws Exception {
        long id = investigating("ONLY-A");

        HttpResponse<String> escalated = act(CASES + "/" + id + "/escalate", "alice",
                "{\"expectedVersion\":2,\"reason\":\"needs a senior analyst\"}");

        assertEquals(200, escalated.statusCode());
        assertTrue(escalated.body().contains("\"status\":\"ESCALATED\""));
    }

    @Test
    void aStaleVersionIsAConflict() throws Exception {
        long id = caseFor("ONLY-A");

        assertError(act(CASES + "/" + id + "/assign", "lead", "{\"expectedVersion\":7,\"owner\":\"alice\"}"),
                409, "CASE_VERSION_CONFLICT");
    }

    @Test
    void anUnknownCaseIsNotFound() throws Exception {
        assertError(get(CASES + "/999999"), 404, "CASE_NOT_FOUND");
        assertError(act(CASES + "/999999/assign", "lead", "{\"expectedVersion\":0,\"owner\":\"alice\"}"),
                404, "CASE_NOT_FOUND");
    }

    @Test
    void anIllegalTransitionIsAConflict() throws Exception {
        long id = caseFor("ONLY-A");

        HttpResponse<String> response = act(CASES + "/" + id + "/investigate", "alice", "{\"expectedVersion\":0}");

        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("ILLEGAL_STATE_TRANSITION")
                || response.body().contains("CASE_OWNER_REQUIRED"), response.body());
    }

    @Test
    void sidesThatDoNotAddUpAreUnprocessable() throws Exception {
        long id = investigating("ONLY-A");

        assertError(act(CASES + "/" + id + "/match", "alice", matchBody(2, "why", "ONLY-A", "LATE-2")),
                422, "MATCH_INVALID");
        assertTrue(get(CASES + "/" + id).body().contains("\"status\":\"INVESTIGATING\""));
    }

    @Test
    void commentsAreRecordedAndShownOnTheCase() throws Exception {
        long id = caseFor("ONLY-A");

        HttpResponse<String> response = act(CASES + "/" + id + "/comments", "alice", "{\"body\":\"Called the bank\"}");

        assertEquals(201, response.statusCode());
        assertTrue(response.body().contains("\"ordinal\":1"));
        assertTrue(get(CASES + "/" + id).body().contains("Called the bank"));
    }

    @Test
    void unmatchingAResolvedCaseReopensItAsANewReviewCase() throws Exception {
        long id = investigating("ONLY-A");
        act(CASES + "/" + id + "/match", "alice", matchBody(2, "first", "ONLY-A", "LATE-1"));

        HttpResponse<String> reopened = act(CASES + "/" + id + "/unmatch", "bob", "{\"reason\":\"Wrong payer\"}");

        assertEquals(201, reopened.statusCode());
        assertTrue(reopened.headers().firstValue("Location").isPresent());
        assertTrue(reopened.body().contains("\"status\":\"OPEN\""));
        assertTrue(reopened.body().contains("\"kind\":\"LOW_CONFIDENCE\""));
        assertError(act(CASES + "/" + id + "/unmatch", "bob", "{\"reason\":\"again\"}"), 409, "NOTHING_TO_UNMATCH");
    }

    @Test
    void unmatchingAnAutoConfirmedRunMatchOpensAReviewCase() throws Exception {
        int ordinal = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT min(ordinal) FROM run_match WHERE run_id = ? AND band = 'AUTO_CONFIRM'", Integer.class,
                runId));

        HttpResponse<String> reopened = act("/api/v1/reconciliations/runs/" + runId + "/matches/" + ordinal
                + "/unmatch", "bob", "{\"reason\":\"Not the same invoice\"}");

        assertEquals(201, reopened.statusCode());
        assertTrue(reopened.body().contains("\"status\":\"OPEN\""));
        assertTrue(reopened.body().contains("\"amountMinor\":1000"));
    }

    @Test
    void ageingReportCountsTheCurrentUnresolvedSnapshot() throws Exception {
        HttpResponse<String> first = get("/api/v1/reports/ageing?asOf=2026-10-12");
        HttpResponse<String> second = get("/api/v1/reports/ageing?asOf=2026-10-12");

        assertEquals(200, first.statusCode(), first.body());
        assertEquals(first.body(), second.body());
        assertTrue(first.body().contains("\"unresolvedCount\":2"), first.body());

        long id = investigating("ONLY-A");
        HttpResponse<String> matched = act(CASES + "/" + id + "/match", "alice",
                matchBody(2, "same payer", "ONLY-A", "LATE-1"));
        assertEquals(200, matched.statusCode(), matched.body());

        HttpResponse<String> after = get("/api/v1/reports/ageing?asOf=2026-10-12");
        assertEquals(200, after.statusCode(), after.body());
        assertTrue(after.body().contains("\"unresolvedCount\":1"), after.body());
        assertError(get("/api/v1/reports/ageing?asOf=bad-date"), 400, "REQUEST_INVALID");
    }

    @Test
    void overlappingSlaRunsEscalateEachOverdueCaseOnlyOnce() throws Exception {
        var job = context.getBean(com.saumay.payments.application.SlaEscalationService.class);
        long before = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_type = 'exception_case'"
                        + " AND action = 'CASE_ESCALATED'", Long.class);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);

        try {
            java.util.concurrent.Callable<Long> run = () -> {
                ready.countDown();
                if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start timed out");
                }
                return job.run(java.time.LocalDate.parse("2030-01-08"));
            };
            var first = executor.submit(run);
            var second = executor.submit(run);
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();

            long changed = first.get(30, java.util.concurrent.TimeUnit.SECONDS)
                    + second.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(2, changed);
            assertEquals(0, job.run(java.time.LocalDate.parse("2030-01-08")));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT count(*) FROM exception_case WHERE status = 'ESCALATED'", Integer.class));
            assertEquals(before + 2, jdbc.queryForObject(
                    "SELECT count(*) FROM audit_event WHERE entity_type = 'exception_case'"
                            + " AND action = 'CASE_ESCALATED'", Long.class));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void resolutionReportIncludesResolvedCasesAndExcludesOpenCases() throws Exception {
        HttpResponse<String> before = get("/api/v1/reports/resolution-time");
        assertEquals(200, before.statusCode(), before.body());
        assertTrue(before.body().contains("\"resolvedCount\":0"), before.body());

        long id = investigating("ONLY-A");
        HttpResponse<String> matched = act(CASES + "/" + id + "/match", "alice",
                matchBody(2, "same payer", "ONLY-A", "LATE-1"));
        assertEquals(200, matched.statusCode(), matched.body());

        HttpResponse<String> after = get("/api/v1/reports/resolution-time");
        assertEquals(200, after.statusCode(), after.body());
        assertTrue(after.body().contains("\"resolvedCount\":1"), after.body());
        assertTrue(after.body().contains("\"invalidTimeOrderCount\":"), after.body());
    }

    @Test
    void matchReportUsesTheCompletedRunAndItsStoredTierCounts() throws Exception {
        HttpResponse<String> first = get("/api/v1/reports/match/" + runId);
        HttpResponse<String> second = get("/api/v1/reports/match/" + runId);

        assertEquals(200, first.statusCode(), first.body());
        assertEquals(first.body(), second.body());
        assertEquals(runId, number(first.body(), "runId"));
        assertEquals(1, number(first.body(), "matchCount"));
        assertTrue(first.body().contains("\"tier\":\"EXACT\""), first.body());
        assertTrue(first.body().contains("\"band\":\"AUTO_CONFIRM\""), first.body());
        assertError(get("/api/v1/reports/match/999999"), 404, "RUN_NOT_FOUND");
        assertError(get("/api/v1/reports/match/0"), 400, "REQUEST_INVALID");
    }

    @Test
    void workloadReportGroupsUnresolvedCasesAndExcludesResolvedCases() throws Exception {
        HttpResponse<String> initial = get("/api/v1/reports/workload");
        assertEquals(200, initial.statusCode(), initial.body());
        assertTrue(initial.body().contains("\"status\":\"OPEN\""), initial.body());
        assertTrue(initial.body().contains("\"count\":2"), initial.body());

        long id = investigating("ONLY-A");
        HttpResponse<String> inProgress = get("/api/v1/reports/workload");
        assertEquals(200, inProgress.statusCode(), inProgress.body());
        assertTrue(inProgress.body().contains("\"status\":\"INVESTIGATING\""), inProgress.body());
        assertTrue(inProgress.body().contains("\"status\":\"OPEN\""), inProgress.body());

        HttpResponse<String> matched = act(CASES + "/" + id + "/match", "alice",
                matchBody(2, "same payer", "ONLY-A", "LATE-1"));
        assertEquals(200, matched.statusCode(), matched.body());

        HttpResponse<String> after = get("/api/v1/reports/workload");
        assertEquals(200, after.statusCode(), after.body());
        assertTrue(!after.body().contains("\"status\":\"RESOLVED\""), after.body());
        assertTrue(after.body().contains("\"count\":1"), after.body());
        assertEquals(after.body(), get("/api/v1/reports/workload").body());
    }

    @Test
    void operationalMetricsExposeQueueDepthAndRequestTimers() throws Exception {
        context.getBean(com.saumay.payments.config.WorkloadMetrics.class).refresh();
        var meters = context.getBean(io.micrometer.core.instrument.MeterRegistry.class);

        double queueDepth = meters.find("exception_queue_depth").gauges().stream()
                .mapToDouble(io.micrometer.core.instrument.Gauge::value)
                .sum();
        assertEquals(2.0, queueDepth);
        assertTrue(meters.find("ingestion_request_duration").timer() != null);
        assertTrue(meters.find("reconciliation_request_duration").timer() != null);
        assertTrue(meters.find("sla_breach_count").counter() != null);

        HttpResponse<String> scrape = get("/actuator/prometheus");
        assertEquals(200, scrape.statusCode(), scrape.body());
        assertTrue(scrape.body().contains("exception_queue_depth"), scrape.body());
        assertTrue(scrape.body().contains("ingestion_request_duration_seconds_count"), scrape.body());
        assertTrue(scrape.body().contains("reconciliation_request_duration_seconds_count"), scrape.body());
    }

    @Test
    void latestRunAndExceptionMetricsUseTheDocumentedDenominators() throws Exception {
        context.getBean(com.saumay.payments.config.OperationalRunMetrics.class).refresh();
        var meters = context.getBean(io.micrometer.core.instrument.MeterRegistry.class);

        double rate = meters.find("reconciliation_match_rate").gauge().value();
        assertEquals(1.0 / 3.0, rate, 0.000001);
        assertEquals(1.0, meters.find("matching_tier_distribution")
                .tag("tier", "EXACT").gauge().value());
        assertEquals(2.0, meters.find("reconciliation_exception_count")
                .tag("kind", "MISSING_LEDGER").gauge().value());

        HttpResponse<String> scrape = get("/actuator/prometheus");
        assertEquals(200, scrape.statusCode(), scrape.body());
        assertTrue(scrape.body().contains("reconciliation_match_rate"), scrape.body());
        assertTrue(scrape.body().contains("matching_tier_distribution"), scrape.body());
        assertTrue(scrape.body().contains("reconciliation_exception_count"), scrape.body());
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(
            org.springframework.boot.test.system.OutputCaptureExtension.class)
    void timedRequestLogsAreJsonAndContainTheCorrelationId(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        HttpResponse<String> response = call("POST", "/api/v1/ingestions/statements",
                null, "text/csv", STATEMENT);
        assertEquals(200, response.statusCode(), response.body());
        String correlation = response.headers().firstValue("X-Correlation-Id").orElseThrow();

        assertTrue(output.getOut().lines().anyMatch(line ->
                line.startsWith("{") && line.contains("\"correlationId\":\"" + correlation + "\"")
                        && line.contains("operational.request operation=statement")),
                output.getOut());
    }

    private static long investigating(String statementReference) throws Exception {
        long id = caseFor(statementReference);
        assertEquals(200, act(CASES + "/" + id + "/assign", "lead",
                "{\"expectedVersion\":0,\"owner\":\"alice\",\"reason\":\"mine\"}").statusCode());
        assertEquals(200, act(CASES + "/" + id + "/investigate", "alice", "{\"expectedVersion\":1}").statusCode());
        return id;
    }

    private static String matchBody(long version, String reason, String statementReference, String ledgerReference) {
        return "{\"expectedVersion\":" + version + ",\"reason\":\"" + reason + "\",\"statementSeqs\":["
                + seq("statement_line", statementReference) + "],\"ledgerSeqs\":[" + seq("ledger_entry",
                ledgerReference) + "]}";
    }

    private static long caseFor(String statementReference) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT c.id FROM exception_case c JOIN run_exception_line l ON l.run_id = c.origin_run_id"
                        + " AND l.exception_ordinal = c.origin_ordinal AND l.role = 'SUBJECT'"
                        + " JOIN statement_line s ON s.seq = l.statement_seq WHERE s.reference = ?",
                Long.class, statementReference));
    }

    private static long seq(String table, String reference) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT seq FROM " + table + " WHERE reference = ?", Long.class, reference));
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"code\":\"" + code + "\""), response.body());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return call("GET", path, null, null, null);
    }

    private static HttpResponse<String> act(String path, String actor, String body) throws Exception {
        return call("POST", path, actor, "application/json", body);
    }

    private static HttpResponse<String> call(String method, String path, String actor, String contentType,
            String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (actor != null) {
            request.header("X-Actor", actor);
        }
        request.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<Long> ids(String body) {
        Matcher m = Pattern.compile("\"id\":(\\d+)").matcher(body);
        List<Long> found = new ArrayList<>();
        while (m.find()) {
            found.add(Long.parseLong(m.group(1)));
        }
        return found;
    }

    private static long number(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":(\\d+)").matcher(body);
        assertTrue(m.find(), field + " not found in " + body);
        return Long.parseLong(m.group(1));
    }
}