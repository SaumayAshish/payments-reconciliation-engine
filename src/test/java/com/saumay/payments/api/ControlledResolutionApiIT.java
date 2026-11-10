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
import java.util.List;
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
 * Write-offs and maker-checker over real HTTP against the real application and PostgreSQL 17 (FRD-08). Each test
 * starts with two cases from one run: BIG-1 (20,000.00 GBP, above the default threshold) and SMALL-1 (30.00 GBP), both
 * missing from the ledger, so both are about a real statement line that a write-off can claim.
 */
class ControlledResolutionApiIT {

    private static final String HEADER =
            "account,booking_date,value_date,amount,currency,reference,counterparty,narrative";
    private static final String STATEMENT = String.join("\n", HEADER,
            "ACC-1,,2026-10-09,20000.00,GBP,BIG-1,Acme Ltd,payment",
            "ACC-1,,2026-10-09,30.00,GBP,SMALL-1,Acme Ltd,payment");
    private static final String LEDGER = String.join("\n", HEADER,
            "ACC-1,,2026-10-09,1.00,GBP,UNRELATED-1,Other Ltd,fee");
    private static final String LATE = String.join("\n", HEADER,
            "ACC-1,,2026-10-09,20000.00,GBP,LATE-1,Acme Ltd,payment");
    private static final long BIG = 2_000_000;
    private static final long SMALL = 3_000;

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
                "--spring.profiles.active=dev",
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
        jdbc.execute("TRUNCATE approval_request, line_claim, case_attachment, case_comment, case_assignment,"
                + " case_activity, exception_case, run_note, run_carried_forward, run_exception_line, run_exception,"
                + " run_match_line, run_match, reconciliation_run_statement_batch, reconciliation_run, rejected_line,"
                + " statement_line, ledger_entry, ingestion_file RESTART IDENTITY CASCADE");
        jdbc.execute("ALTER TABLE config_version_seal DISABLE TRIGGER USER");
        jdbc.execute("ALTER TABLE config_approval_threshold DISABLE TRIGGER USER");
        jdbc.execute("ALTER TABLE config_version DISABLE TRIGGER USER");
        try {
            jdbc.execute("TRUNCATE config_version_seal, config_approval_threshold,"
                    + " config_version RESTART IDENTITY CASCADE");
        } finally {
            jdbc.execute("ALTER TABLE config_version ENABLE TRIGGER USER");
            jdbc.execute("ALTER TABLE config_approval_threshold ENABLE TRIGGER USER");
            jdbc.execute("ALTER TABLE config_version_seal ENABLE TRIGGER USER");
        }
        jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER USER");
        jdbc.execute("TRUNCATE audit_event");
        jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER USER");
        long statements = number(call("POST", "/api/v1/ingestions/statements", null, "text/csv", STATEMENT).body(),
                "ingestionId");
        call("POST", "/api/v1/ingestions/ledger", null, "text/csv", LEDGER);
        HttpResponse<String> run = call("POST", "/api/v1/reconciliations/runs", null, "application/json",
                "{\"businessDate\":\"2026-10-09\",\"statementIngestionIds\":[" + statements + "]}");
        assertEquals(201, run.statusCode());
        context.getBean(CaseIntakeService.class).openCases(number(run.body(), "id"));
        call("POST", "/api/v1/ingestions/ledger", null, "text/csv", LATE);
    }

    @Test
    void anAmountAtOrBelowTheThresholdIsWrittenOffAtOnce() throws Exception {
        long id = investigating(caseOf(SMALL));
        HttpResponse<String> response = resolve(id, "alice", "WRITE_OFF");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("RESOLVED", caseValue(id, "status"));
        assertEquals("WRITE_OFF", caseValue(id, "resolution_type"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM line_claim WHERE case_id = ? AND source = 'WRITE_OFF' AND action = 'CLAIM'",
                Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM approval_request", Integer.class));
    }

    @Test
    void anAmountAboveTheThresholdWaitsForApprovalAndTheCaseStaysInvestigating() throws Exception {
        long id = investigating(caseOf(BIG));
        HttpResponse<String> response = resolve(id, "alice", "WRITE_OFF");

        assertEquals(202, response.statusCode(), response.body());
        assertEquals("PENDING", text(response.body(), "status"));
        assertEquals("alice", text(response.body(), "maker"));
        assertTrue(response.headers().firstValue("Location").orElse("").startsWith("/api/v1/approvals/"));
        assertEquals("INVESTIGATING", caseValue(id, "status"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM line_claim WHERE case_id = ?", Integer.class, id));
        assertEquals(List.of("APPROVAL_REQUESTED"), auditActions("approval_request"));
    }

    @Test
    void theMakerCannotApproveTheirOwnRequestAndTheAttemptIsAudited() throws Exception {
        long id = investigating(caseOf(BIG));
        long approval = number(resolve(id, "carol", "WRITE_OFF").body(), "id");

        HttpResponse<String> denied = call("POST", "/api/v1/approvals/" + approval + "/approve", "CAROL",
                "application/json", "{}");

        assertEquals(403, denied.statusCode(), denied.body());
        assertEquals("SEPARATION_OF_DUTIES_VIOLATION", text(denied.body(), "code"));
        assertEquals(List.of("APPROVAL_REQUESTED", "APPROVAL_DENIED"), auditActions("approval_request"));
        assertEquals("PENDING", approvalValue(approval, "status"));
        assertEquals("INVESTIGATING", caseValue(id, "status"));
    }

    @Test
    void aDifferentControllerApprovesAndTheCaseIsResolvedAndAuditedTogether() throws Exception {
        long id = investigating(caseOf(BIG));
        long approval = number(resolve(id, "carol", "WRITE_OFF").body(), "id");

        HttpResponse<String> approved = call("POST", "/api/v1/approvals/" + approval + "/approve", "dave",
                "application/json", "{\"note\":\"evidence seen\"}");

        assertEquals(200, approved.statusCode(), approved.body());
        assertEquals("APPROVED", text(approved.body(), "status"));
        assertEquals("dave", text(approved.body(), "checker"));
        assertEquals("RESOLVED", caseValue(id, "status"));
        assertEquals("WRITE_OFF", caseValue(id, "resolution_type"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM line_claim WHERE case_id = ? AND source = 'WRITE_OFF'", Integer.class, id));
        assertEquals(List.of("APPROVAL_REQUESTED", "APPROVAL_APPROVED"), auditActions("approval_request"));
        assertTrue(auditActions("exception_case").contains("CASE_RESOLVED"));

        HttpResponse<String> again = call("POST", "/api/v1/approvals/" + approval + "/approve", "dave",
                "application/json", "{}");
        assertEquals(409, again.statusCode());
        assertEquals("APPROVAL_ALREADY_DECIDED", text(again.body(), "code"));
    }

    @Test
    void onlyAControllerCanDecideAndAnAuditorCannotResolve() throws Exception {
        long id = investigating(caseOf(BIG));
        long approval = number(resolve(id, "carol", "WRITE_OFF").body(), "id");

        HttpResponse<String> analyst = call("POST", "/api/v1/approvals/" + approval + "/approve", "alice",
                "application/json", "{}");
        assertEquals(403, analyst.statusCode());
        assertEquals("ACCESS_DENIED", text(analyst.body(), "code"));

        long other = investigating(caseOf(SMALL));
        HttpResponse<String> auditor = resolve(other, "erin", "WRITE_OFF");
        assertEquals(403, auditor.statusCode());
        assertEquals("ACCESS_DENIED", text(auditor.body(), "code"));
        assertEquals("INVESTIGATING", caseValue(other, "status"));
    }

    @Test
    void theActingUserMustBePresentAndKnown() throws Exception {
        long id = investigating(caseOf(SMALL));
        HttpResponse<String> none = call("POST", "/api/v1/exceptions/" + id + "/resolve", null, "application/json",
                resolveBody(id, "WRITE_OFF"));
        assertEquals(400, none.statusCode());
        assertEquals("ACTOR_REQUIRED", text(none.body(), "code"));

        HttpResponse<String> unknown = resolve(id, "mallory", "WRITE_OFF");
        assertEquals(401, unknown.statusCode());
        assertEquals("UNKNOWN_ACTOR", text(unknown.body(), "code"));
        assertEquals("INVESTIGATING", caseValue(id, "status"));
    }

    @Test
    void whileARequestIsPendingTheCaseCannotBeMatchedOrWrittenOffAgain() throws Exception {
        long id = investigating(caseOf(BIG));
        resolve(id, "carol", "WRITE_OFF");

        HttpResponse<String> second = resolve(id, "bob", "ADJUSTMENT");
        assertEquals(409, second.statusCode());
        assertEquals("APPROVAL_PENDING", text(second.body(), "code"));

        long statementSeq = jdbc.queryForObject(
                "SELECT l.statement_seq FROM exception_case c JOIN run_exception_line l ON l.run_id = c.origin_run_id"
                        + " AND l.exception_ordinal = c.origin_ordinal AND l.role = 'SUBJECT' WHERE c.id = ?",
                Long.class, id);
        long ledgerSeq = jdbc.queryForObject("SELECT max(seq) FROM ledger_entry", Long.class);
        HttpResponse<String> match = call("POST", "/api/v1/exceptions/" + id + "/match", "alice", "application/json",
                "{\"expectedVersion\":" + version(id) + ",\"reason\":\"late ledger\",\"statementSeqs\":["
                        + statementSeq + "],\"ledgerSeqs\":[" + ledgerSeq + "]}");
        assertEquals(409, match.statusCode(), match.body());
        assertEquals("APPROVAL_PENDING", text(match.body(), "code"));
        assertEquals("INVESTIGATING", caseValue(id, "status"));
    }

    @Test
    void aRejectionLeavesTheCaseOpenAndANewRequestIsAllowed() throws Exception {
        long id = investigating(caseOf(BIG));
        long approval = number(resolve(id, "carol", "WRITE_OFF").body(), "id");

        HttpResponse<String> missingNote = call("POST", "/api/v1/approvals/" + approval + "/reject", "dave",
                "application/json", "{}");
        assertEquals(400, missingNote.statusCode());

        HttpResponse<String> rejected = call("POST", "/api/v1/approvals/" + approval + "/reject", "dave",
                "application/json", "{\"note\":\"no supporting evidence\"}");
        assertEquals(200, rejected.statusCode(), rejected.body());
        assertEquals("REJECTED", text(rejected.body(), "status"));
        assertEquals("INVESTIGATING", caseValue(id, "status"));
        assertEquals(List.of("APPROVAL_REQUESTED", "APPROVAL_REJECTED"), auditActions("approval_request"));

        assertEquals(202, resolve(id, "carol", "ADJUSTMENT").statusCode());
    }

    @Test
    void aLineClaimedWhilePendingFailsTheApprovalAndLeavesItPending() throws Exception {
        long id = investigating(caseOf(BIG));
        long approval = number(resolve(id, "carol", "WRITE_OFF").body(), "id");
        long statementSeq = jdbc.queryForObject(
                "SELECT l.statement_seq FROM exception_case c JOIN run_exception_line l ON l.run_id = c.origin_run_id"
                        + " AND l.exception_ordinal = c.origin_ordinal AND l.role = 'SUBJECT' WHERE c.id = ?",
                Long.class, id);
        long otherCase = caseOf(SMALL);
        jdbc.update("INSERT INTO line_claim (action, statement_seq, source, case_id, actor)"
                + " VALUES ('CLAIM', ?, 'MANUAL_MATCH', ?, 'x')", statementSeq, otherCase);

        HttpResponse<String> response = call("POST", "/api/v1/approvals/" + approval + "/approve", "dave",
                "application/json", "{}");

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("MATCH_LINE_ALREADY_CLAIMED", text(response.body(), "code"));
        assertEquals("PENDING", approvalValue(approval, "status"));
        assertEquals("INVESTIGATING", caseValue(id, "status"));
        assertEquals(List.of("APPROVAL_REQUESTED"), auditActions("approval_request"));
    }

    @Test
    void aWrittenOffCaseCannotBeUnmatched() throws Exception {
        long id = investigating(caseOf(SMALL));
        assertEquals(200, resolve(id, "alice", "WRITE_OFF").statusCode());

        HttpResponse<String> response = call("POST", "/api/v1/exceptions/" + id + "/unmatch", "alice",
                "application/json", "{\"reason\":\"changed my mind\"}");

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("NOTHING_TO_UNMATCH", text(response.body(), "code"));
        assertEquals("RESOLVED", caseValue(id, "status"));
    }

    @Test
    void manualEscalationIsBlockedWhileApprovalIsPending() throws Exception {
        long id = investigating(caseOf(BIG));
        HttpResponse<String> requested = resolve(id, "carol", "WRITE_OFF");
        assertEquals(202, requested.statusCode(), requested.body());
        long approval = number(requested.body(), "id");
        long beforeVersion = version(id);
        List<String> beforeActions = auditActions("exception_case");

        HttpResponse<String> response = call("POST", "/api/v1/exceptions/" + id + "/escalate",
                "alice", "application/json",
                "{\"expectedVersion\":" + beforeVersion + ",\"reason\":\"needs review\"}");

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("APPROVAL_PENDING", text(response.body(), "code"));
        assertEquals("INVESTIGATING", caseValue(id, "status"));
        assertEquals(beforeVersion, version(id));
        assertEquals("PENDING", approvalValue(approval, "status"));
        assertEquals(beforeActions, auditActions("exception_case"));
    }

    @Test
    void theStoreBlocksManualEscalationEvenIfTheEarlyGuardMissedTheRequest() throws Exception {
        long id = investigating(caseOf(BIG));
        assertEquals(202, resolve(id, "carol", "WRITE_OFF").statusCode());
        long beforeVersion = version(id);
        List<String> beforeActions = auditActions("exception_case");
        var service = new com.saumay.payments.application.CaseService(
                context.getBean(com.saumay.payments.application.CaseStore.class),
                java.time.Clock.systemUTC());

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                com.saumay.payments.application.ApprovalRejectedException.class,
                () -> service.escalate(id, beforeVersion, "alice", "needs review", false));

        assertEquals(com.saumay.payments.application.ApprovalErrorCode.APPROVAL_PENDING, error.code());
        assertEquals("INVESTIGATING", caseValue(id, "status"));
        assertEquals(beforeVersion, version(id));
        assertEquals(beforeActions, auditActions("exception_case"));
    }

    @Test
    void theStoreBlocksMatchingEvenIfTheEarlyGuardMissedTheRequest() throws Exception {
        long id = investigating(caseOf(BIG));
        assertEquals(202, resolve(id, "carol", "WRITE_OFF").statusCode());
        long beforeVersion = version(id);
        long statementSeq = jdbc.queryForObject(
                "SELECT l.statement_seq FROM exception_case c JOIN run_exception_line l ON l.run_id = c.origin_run_id"
                        + " AND l.exception_ordinal = c.origin_ordinal AND l.role = 'SUBJECT' WHERE c.id = ?",
                Long.class, id);
        long ledgerSeq = jdbc.queryForObject("SELECT max(seq) FROM ledger_entry", Long.class);
        var service = new com.saumay.payments.application.CaseMatchService(
                context.getBean(com.saumay.payments.application.CaseMatchStore.class),
                context.getBean(com.saumay.payments.domain.cases.RoutingConfig.class),
                java.time.Clock.systemUTC());

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                com.saumay.payments.application.ApprovalRejectedException.class,
                () -> service.resolve(id, beforeVersion, "alice", "late ledger",
                        List.of(statementSeq), List.of(ledgerSeq)));

        assertEquals(com.saumay.payments.application.ApprovalErrorCode.APPROVAL_PENDING, error.code());
        assertEquals("INVESTIGATING", caseValue(id, "status"));
        assertEquals(beforeVersion, version(id));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM line_claim WHERE case_id = ?", Integer.class, id));
    }

    @Test
    void escalationWaitsForAnUncommittedApprovalAndThenRejectsIt() throws Exception {
        long id = investigating(caseOf(BIG));
        long beforeVersion = version(id);
        var service = new com.saumay.payments.application.CaseService(
                context.getBean(com.saumay.payments.application.CaseStore.class),
                java.time.Clock.systemUTC());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();

        try (var connection = context.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.prepareStatement(
                        "SELECT id FROM exception_case WHERE id = ? FOR UPDATE")) {
                    lock.setLong(1, id);
                    try (var rows = lock.executeQuery()) {
                        assertTrue(rows.next());
                    }
                }
                try (var insert = connection.prepareStatement(
                        "INSERT INTO approval_request"
                                + " (case_id, action, amount_minor, currency, reason, maker)"
                                + " VALUES (?, 'WRITE_OFF', ?, 'GBP', 'test', 'carol')")) {
                    insert.setLong(1, id);
                    insert.setLong(2, BIG);
                    assertEquals(1, insert.executeUpdate());
                }

                var escalation = executor.submit(() -> service.escalate(
                        id, beforeVersion, "alice", "needs review", false));

                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    Integer count = jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity"
                                    + " WHERE datname = current_database()"
                                    + " AND wait_event_type = 'Lock'"
                                    + " AND query LIKE 'SELECT id FROM exception_case WHERE id = %FOR UPDATE%'",
                            Integer.class);
                    if (count != null && count > 0) {
                        waiting = true;
                        break;
                    }
                    Thread.sleep(25);
                }
                assertTrue(waiting, "Escalation did not reach the case lock");
                connection.commit();

                var failure = org.junit.jupiter.api.Assertions.assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> escalation.get(10, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(failure.getCause()
                        instanceof com.saumay.payments.application.ApprovalRejectedException);
                var rejected = (com.saumay.payments.application.ApprovalRejectedException) failure.getCause();
                assertEquals(com.saumay.payments.application.ApprovalErrorCode.APPROVAL_PENDING,
                        rejected.code());
                assertEquals("INVESTIGATING", caseValue(id, "status"));
                assertEquals(beforeVersion, version(id));
            } finally {
                connection.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void approvalWaitsForACaseChangeAndThenRejectsTheStaleVersion() throws Exception {
        long id = investigating(caseOf(BIG));
        long beforeVersion = version(id);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();

        try (var connection = context.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var update = connection.prepareStatement(
                        "UPDATE exception_case SET status = 'ESCALATED', version = version + 1"
                                + " WHERE id = ? AND version = ?")) {
                    update.setLong(1, id);
                    update.setLong(2, beforeVersion);
                    assertEquals(1, update.executeUpdate());
                }

                var approval = executor.submit(() -> call("POST",
                        "/api/v1/exceptions/" + id + "/resolve", "carol", "application/json",
                        "{\"expectedVersion\":" + beforeVersion
                                + ",\"action\":\"WRITE_OFF\",\"reason\":\"stale item\"}"));

                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    Integer count = jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity"
                                    + " WHERE datname = current_database()"
                                    + " AND wait_event_type = 'Lock'"
                                    + " AND query LIKE 'SELECT status, version, amount_minor, currency"
                                    + " FROM exception_case WHERE id = %FOR UPDATE%'",
                            Integer.class);
                    if (count != null && count > 0) {
                        waiting = true;
                        break;
                    }
                    Thread.sleep(25);
                }
                assertTrue(waiting, "Approval submission did not reach the case lock");
                connection.commit();

                HttpResponse<String> response =
                        approval.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(409, response.statusCode(), response.body());
                assertEquals("CASE_VERSION_CONFLICT", text(response.body(), "code"));
                assertEquals("ESCALATED", caseValue(id, "status"));
                assertEquals(beforeVersion + 1, version(id));
                assertEquals(0, jdbc.queryForObject(
                        "SELECT count(*) FROM approval_request WHERE case_id = ?", Integer.class, id));
            } finally {
                connection.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void anAuditorCanVerifyTheIntactAuditChain() throws Exception {
        HttpResponse<String> response = call(
                "GET", "/api/v1/audit/verify", "erin", "application/json", null);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("OK", text(response.body(), "status"));
        long count = jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class);
        assertTrue(count > 0);
        assertEquals(count, number(response.body(), "count"));
        assertEquals(count, number(response.body(), "headSeq"));
        assertEquals(jdbc.queryForObject(
                "SELECT current_hash FROM audit_event ORDER BY seq DESC LIMIT 1", String.class),
                text(response.body(), "headHash"));
    }

    @Test
    void auditVerificationIdentifiesTheFirstTamperedEvent() throws Exception {
        long first = jdbc.queryForObject("SELECT min(seq) FROM audit_event", Long.class);
        jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER USER");
        try {
            jdbc.update("UPDATE audit_event SET actor = ? WHERE seq = ?", "tampered", first);
        } finally {
            jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER USER");
        }

        HttpResponse<String> response = call(
                "GET", "/api/v1/audit/verify", "root", "application/json", null);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("FAILED", text(response.body(), "status"));
        assertEquals(first, number(response.body(), "firstBadSeq"));
        assertEquals("HASH_MISMATCH", text(response.body(), "reason"));
        assertEquals(0, number(response.body(), "verifiedCount"));
    }

    @Test
    void auditVerificationRequiresAnAuditorOrAdministrator() throws Exception {
        HttpResponse<String> response = call(
                "GET", "/api/v1/audit/verify", "alice", "application/json", null);

        assertEquals(403, response.statusCode(), response.body());
        assertEquals("ACCESS_DENIED", text(response.body(), "code"));
    }

    @Test
    void anAuditorCanReadAndPageAnEntityAuditHistory() throws Exception {
        long id = investigating(caseOf(BIG));
        assertEquals(202, resolve(id, "carol", "WRITE_OFF").statusCode());

        String path = "/api/v1/audit/exception_case/" + id;
        HttpResponse<String> first = call("GET", path + "?limit=1",
                "erin", "application/json", null);
        assertEquals(200, first.statusCode(), first.body());
        assertEquals("exception_case", text(first.body(), "entityType"));
        assertEquals(Long.toString(id), text(first.body(), "entityId"));
        long firstSeq = number(first.body(), "seq");
        assertEquals(jdbc.queryForObject(
                "SELECT min(seq) FROM audit_event WHERE entity_type = 'exception_case' AND entity_id = ?",
                Long.class, Long.toString(id)), firstSeq);

        HttpResponse<String> next = call("GET", path + "?limit=1&afterSeq=" + firstSeq,
                "erin", "application/json", null);
        assertEquals(200, next.statusCode(), next.body());
        assertEquals(jdbc.queryForObject(
                "SELECT min(seq) FROM audit_event WHERE entity_type = 'exception_case'"
                        + " AND entity_id = ? AND seq > ?",
                Long.class, Long.toString(id), firstSeq), number(next.body(), "seq"));

        HttpResponse<String> missing = call("GET", "/api/v1/audit/exception_case/no-such-id",
                "erin", "application/json", null);
        assertEquals(200, missing.statusCode(), missing.body());
        assertTrue(missing.body().contains("\"events\":[]"), missing.body());
    }

    @Test
    void auditHistoryRejectsUnauthorisedAccessAndInvalidPagination() throws Exception {
        long id = caseOf(BIG);
        String path = "/api/v1/audit/exception_case/" + id;

        HttpResponse<String> forbidden = call("GET", path, "alice", "application/json", null);
        assertEquals(403, forbidden.statusCode(), forbidden.body());
        assertEquals("ACCESS_DENIED", text(forbidden.body(), "code"));

        HttpResponse<String> invalidLimit = call("GET", path + "?limit=501",
                "erin", "application/json", null);
        assertEquals(400, invalidLimit.statusCode(), invalidLimit.body());

        HttpResponse<String> invalidSequence = call("GET", path + "?afterSeq=-1",
                "erin", "application/json", null);
        assertEquals(400, invalidSequence.statusCode(), invalidSequence.body());
    }

    @Test
    void anAdminConfigurationChangesTheThresholdAndTheApprovalRecordsItsVersion() throws Exception {
        HttpResponse<String> update = call("PUT", "/api/v1/configuration", "root",
                "application/json", "{\"perCurrencyMinor\":{\"GBP\":100}}");
        assertEquals(200, update.statusCode(), update.body());
        long configVersion = number(update.body(), "version");
        assertTrue(configVersion > 0);

        long id = investigating(caseOf(SMALL));
        HttpResponse<String> requested = resolve(id, "alice", "WRITE_OFF");
        assertEquals(202, requested.statusCode(), requested.body());
        long approval = number(requested.body(), "id");
        assertEquals(configVersion, jdbc.queryForObject(
                "SELECT config_version FROM approval_request WHERE id = ?", Long.class, approval));

        HttpResponse<String> replacement = call("PUT", "/api/v1/configuration", "root",
                "application/json", "{\"perCurrencyMinor\":{}}");
        assertEquals(200, replacement.statusCode(), replacement.body());
        assertTrue(number(replacement.body(), "version") > configVersion);
        assertEquals(configVersion, jdbc.queryForObject(
                "SELECT config_version FROM approval_request WHERE id = ?", Long.class, approval));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'CONFIG_CHANGED'", Integer.class));
    }

    @Test
    void configurationUpdatesRequireAdminAndValidThresholds() throws Exception {
        HttpResponse<String> forbidden = call("PUT", "/api/v1/configuration", "alice",
                "application/json", "{\"perCurrencyMinor\":{\"GBP\":100}}");
        assertEquals(403, forbidden.statusCode(), forbidden.body());

        HttpResponse<String> invalid = call("PUT", "/api/v1/configuration", "root",
                "application/json", "{\"perCurrencyMinor\":{\"gbp\":-1}}");
        assertEquals(400, invalid.statusCode(), invalid.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM config_version", Integer.class));
    }

    @Test
    void auditVerificationAcceptsAHistoricalAnchorAndReportsMismatch() throws Exception {
        String hash = jdbc.queryForObject(
                "SELECT current_hash FROM audit_event WHERE seq = 1", String.class);
        String path = "/api/v1/audit/verify?expectedSeq=1&expectedHash=";

        HttpResponse<String> valid = call("GET", path + hash, "erin", "application/json", null);
        assertEquals(200, valid.statusCode(), valid.body());
        assertEquals("OK", text(valid.body(), "status"));

        HttpResponse<String> mismatch = call("GET", path + "f".repeat(64),
                "erin", "application/json", null);
        assertEquals(200, mismatch.statusCode(), mismatch.body());
        assertEquals("FAILED", text(mismatch.body(), "status"));
        assertEquals("HEAD_MISMATCH", text(mismatch.body(), "reason"));
        assertEquals(1, number(mismatch.body(), "expectedSeq"));

        HttpResponse<String> incomplete = call("GET", "/api/v1/audit/verify?expectedSeq=1",
                "erin", "application/json", null);
        assertEquals(400, incomplete.statusCode(), incomplete.body());

        HttpResponse<String> malformed = call("GET",
                "/api/v1/audit/verify?expectedSeq=1&expectedHash=bad",
                "erin", "application/json", null);
        assertEquals(400, malformed.statusCode(), malformed.body());
    }

    @Test
    void anSlaEscalatedPendingApprovalCanProceedAfterInvestigationRestarts() throws Exception {
        long id = investigating(caseOf(BIG));
        HttpResponse<String> requested = resolve(id, "carol", "WRITE_OFF");
        assertEquals(202, requested.statusCode(), requested.body());
        long approval = number(requested.body(), "id");

        context.getBean(com.saumay.payments.application.SlaEscalationService.class)
                .run(java.time.LocalDate.parse("2030-01-08"));
        assertEquals("ESCALATED", caseValue(id, "status"));
        assertEquals("PENDING", approvalValue(approval, "status"));

        HttpResponse<String> premature = call("POST", "/api/v1/approvals/" + approval + "/approve",
                "dave", "application/json", "{}");
        assertEquals(409, premature.statusCode(), premature.body());
        assertEquals("PENDING", approvalValue(approval, "status"));

        HttpResponse<String> restarted = call("POST", "/api/v1/exceptions/" + id + "/investigate",
                "alice", "application/json", "{\"expectedVersion\":" + version(id) + "}");
        assertEquals(200, restarted.statusCode(), restarted.body());

        HttpResponse<String> approved = call("POST", "/api/v1/approvals/" + approval + "/approve",
                "dave", "application/json", "{}");
        assertEquals(200, approved.statusCode(), approved.body());
        assertEquals("RESOLVED", caseValue(id, "status"));
    }

    // ---- helpers ----

    private long caseOf(long amountMinor) {
        return jdbc.queryForObject("SELECT id FROM exception_case WHERE amount_minor = ? AND kind = 'MISSING_LEDGER'",
                Long.class, amountMinor);
    }

    private long version(long caseId) {
        return jdbc.queryForObject("SELECT version FROM exception_case WHERE id = ?", Long.class, caseId);
    }

    private String caseValue(long caseId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM exception_case WHERE id = ?", String.class, caseId);
    }

    private String approvalValue(long approvalId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM approval_request WHERE id = ?", String.class,
                approvalId);
    }

    private List<String> auditActions(String entityType) {
        return jdbc.queryForList("SELECT action FROM audit_event WHERE entity_type = ? ORDER BY seq", String.class,
                entityType);
    }

    private long investigating(long caseId) throws Exception {
        HttpResponse<String> assigned = call("POST", "/api/v1/exceptions/" + caseId + "/assign", "alice",
                "application/json", "{\"expectedVersion\":" + version(caseId) + ",\"owner\":\"alice\",\"reason\":\"t\"}");
        assertEquals(200, assigned.statusCode(), assigned.body());
        HttpResponse<String> started = call("POST", "/api/v1/exceptions/" + caseId + "/investigate", "alice",
                "application/json", "{\"expectedVersion\":" + version(caseId) + "}");
        assertEquals(200, started.statusCode(), started.body());
        return caseId;
    }

    private String resolveBody(long caseId, String action) {
        return "{\"expectedVersion\":" + version(caseId) + ",\"action\":\"" + action + "\",\"reason\":\"stale item\"}";
    }

    private HttpResponse<String> resolve(long caseId, String actor, String action) throws Exception {
        return call("POST", "/api/v1/exceptions/" + caseId + "/resolve", actor, "application/json",
                resolveBody(caseId, action));
    }

    private static HttpResponse<String> call(String method, String path, String actor, String contentType,
            String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", contentType);
        if (actor != null) {
            builder.header("X-Actor", actor);
        }
        builder.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String text(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(json);
        assertTrue(m.find(), field + " not found in " + json);
        return m.group(1);
    }

    private static long number(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":(\\d+)").matcher(json);
        assertTrue(m.find(), field + " not found in " + json);
        return Long.parseLong(m.group(1));
    }
}