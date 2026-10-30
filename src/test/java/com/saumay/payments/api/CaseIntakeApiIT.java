package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.PaymentsApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
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
 * Opening the cases of a run over real HTTP against the real application and PostgreSQL 17 (FRD-06). The story:
 * REF-1 matches and is auto-confirmed, ONLY-A and ONLY-B have no ledger entry, so the run holds two exceptions.
 * Nothing opens cases in setup: the endpoint under test does it.
 */
class CaseIntakeApiIT {

    private static final String HEADER =
            "account,booking_date,value_date,amount,currency,reference,counterparty,narrative";
    private static final String STATEMENT = String.join("\n", HEADER,
            "ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd,payment",
            "ACC-1,,2026-10-09,30.00,GBP,ONLY-A,Acme Ltd,payment",
            "ACC-1,,2026-10-09,30.00,GBP,ONLY-B,Acme Ltd,payment");
    private static final String LEDGER = String.join("\n", HEADER,
            "ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd,payment");

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
    void aCompletedRunWithTwoExceptions() throws Exception {
        jdbc.execute("TRUNCATE line_claim, case_attachment, case_comment, case_assignment, case_activity,"
                + " exception_case, run_note, run_carried_forward, run_exception_line, run_exception, run_match_line,"
                + " run_match, reconciliation_run_statement_batch, reconciliation_run, rejected_line,"
                + " statement_line, ledger_entry, ingestion_file RESTART IDENTITY CASCADE");
        long statements = number(send("POST", "/api/v1/ingestions/statements", "text/csv", STATEMENT).body(),
                "ingestionId");
        send("POST", "/api/v1/ingestions/ledger", "text/csv", LEDGER);
        HttpResponse<String> run = send("POST", "/api/v1/reconciliations/runs", "application/json",
                "{\"businessDate\":\"2026-10-09\",\"statementIngestionIds\":[" + statements + "]}");
        assertEquals(201, run.statusCode());
        runId = number(run.body(), "id");
    }

    @Test
    void theFirstCallOpensACasePerExceptionAndARepeatOpensNothing() throws Exception {
        HttpResponse<String> first = send("POST", intake(runId), null, null);

        assertEquals(200, first.statusCode());
        assertTrue(first.body().contains("\"exceptions\":2"), first.body());
        assertTrue(first.body().contains("\"created\":2"), first.body());
        assertTrue(first.body().contains("\"alreadyOpen\":0"), first.body());

        HttpResponse<String> second = send("POST", intake(runId), null, null);

        assertEquals(200, second.statusCode());
        assertTrue(second.body().contains("\"created\":0"), second.body());
        assertTrue(second.body().contains("\"alreadyOpen\":2"), second.body());
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM exception_case", Long.class),
                "two calls, still one case per exception");
        HttpResponse<String> queue = send("GET", "/api/v1/exceptions", null, null);
        assertEquals(2, count(queue.body(), "\"routingRule\""), queue.body());
    }

    @Test
    void anUnknownRunIsRefusedWithItsOwnCodeAndOpensNothing() throws Exception {
        HttpResponse<String> response = send("POST", intake(999_999L), null, null);

        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"RUN_NOT_FOUND\""), response.body());
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM exception_case", Long.class));
    }

    private static String intake(long id) {
        return "/api/v1/reconciliations/runs/" + id + "/exceptions";
    }

    private static HttpResponse<String> send(String method, String path, String contentType, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        request.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static long number(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":(\\d+)").matcher(json);
        assertTrue(matcher.find(), "no " + field + " in " + json);
        return Long.parseLong(matcher.group(1));
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            found++;
        }
        return found;
    }
}