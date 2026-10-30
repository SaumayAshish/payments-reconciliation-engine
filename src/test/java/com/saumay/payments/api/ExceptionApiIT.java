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