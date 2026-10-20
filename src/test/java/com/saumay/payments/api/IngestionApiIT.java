package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.PaymentsApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

class IngestionApiIT {

    private static final String HEADER =
            "account,booking_date,value_date,amount,currency,reference,counterparty,narrative";
    private static final String CSV_3 = String.join("\n", HEADER,
            "ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd,payment",
            "ACC-1,,2026-10-09,20.00,GBP,REF-2,Acme Ltd,payment",
            "ACC-1,,2026-10-09,30.00,GBP,REF-3,Acme Ltd,payment");

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
                "--spring.datasource.password=" + postgres.getPassword(),
                "--payments.ingestion.max-bytes=5000");
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
        jdbc.execute("TRUNCATE ingestion_file, statement_line, ledger_entry, rejected_line RESTART IDENTITY CASCADE");
    }

    @Test
    void tenUploadsOfTheSameStatementCreateExactlyOneIngestion() throws Exception {
        HttpResponse<String> first = post("/statements", "text/csv", CSV_3.getBytes(StandardCharsets.UTF_8));
        assertEquals(201, first.statusCode());
        assertTrue(first.body().contains("\"duplicate\":false"));
        assertTrue(first.headers().firstValue("Location").isPresent());

        for (int i = 2; i <= 10; i++) {
            HttpResponse<String> again = post("/statements", "text/csv", CSV_3.getBytes(StandardCharsets.UTF_8));
            assertEquals(200, again.statusCode());
            assertTrue(again.body().contains("\"duplicate\":true"));
        }

        assertEquals(1, count("ingestion_file"));
        assertEquals(3, count("statement_line"));
    }

    @Test
    void eightConcurrentUploadsOfTheSameBytesCreateOneIngestion() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() ->
                        post("/statements", "text/csv", CSV_3.getBytes(StandardCharsets.UTF_8)).statusCode()));
            }
            int created = 0;
            int ok = 0;
            for (Future<Integer> result : results) {
                int status = result.get();
                if (status == 201) {
                    created++;
                } else if (status == 200) {
                    ok++;
                }
            }
            assertEquals(1, created);
            assertEquals(7, ok);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("ingestion_file"));
        assertEquals(3, count("statement_line"));
    }

    @Test
    void theSameBytesAsALedgerFileAreADifferentIngestion() throws Exception {
        assertEquals(201, post("/statements", "text/csv", CSV_3.getBytes(StandardCharsets.UTF_8)).statusCode());
        assertEquals(201, post("/ledger", "text/csv", CSV_3.getBytes(StandardCharsets.UTF_8)).statusCode());

        assertEquals(2, count("ingestion_file"));
        assertEquals(3, count("statement_line"));
        assertEquals(3, count("ledger_entry"));
    }

    @Test
    void invalidRowsAreStoredAndVisibleThroughGet() throws Exception {
        String csv = String.join("\n", HEADER,
                "ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd,payment",
                "ACC-1,,2026-10-09,10.00,XX1,REF-2,Acme Ltd,payment");

        HttpResponse<String> created = post("/statements", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(201, created.statusCode());
        assertTrue(created.body().contains("\"acceptedCount\":1"));
        assertTrue(created.body().contains("\"rejectedCount\":1"));

        String location = created.headers().firstValue("Location").orElseThrow();
        HttpResponse<String> detail = get(location);
        assertEquals(200, detail.statusCode());
        assertTrue(detail.body().contains("INVALID_CURRENCY"));
        assertTrue(detail.body().contains("\"kind\":\"STATEMENT\""));
        assertEquals(1, count("rejected_line"));
    }

    @Test
    void aCamtStatementIsAccepted() throws Exception {
        String xml = "<Document><Stmt><Acct><Id><IBAN>GB00SYNTH0001</IBAN></Id></Acct><Ntry>"
                + "<Amt Ccy=\"GBP\">1250.50</Amt><CdtDbtInd>CRDT</CdtDbtInd>"
                + "<ValDt><Dt>2026-10-10</Dt></ValDt>"
                + "<NtryDtls><TxDtls><Refs><EndToEndId>E2E-1</EndToEndId></Refs></TxDtls></NtryDtls>"
                + "</Ntry></Stmt></Document>";

        HttpResponse<String> response = post("/statements", "application/xml", xml.getBytes(StandardCharsets.UTF_8));

        assertEquals(201, response.statusCode());
        assertTrue(response.body().contains("\"acceptedCount\":1"));
        assertEquals(125050L, jdbc.queryForObject("SELECT amount_minor FROM statement_line", Long.class));
    }

    @Test
    void aHeaderOnlyFileIsRefusedWithATypedErrorAndNoIngestionRow() throws Exception {
        HttpResponse<String> response = post("/statements", "text/csv", HEADER.getBytes(StandardCharsets.UTF_8));

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("INGESTION_NO_DATA_ROWS"));
        assertTrue(response.body().contains("correlationId"));
        assertEquals(0, count("ingestion_file"));
    }

    @Test
    void anOversizedUploadIsRefusedAndNothingIsStored() throws Exception {
        byte[] big = "x".repeat(6000).getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = post("/statements", "text/csv", big);

        assertEquals(413, response.statusCode());
        assertTrue(response.body().contains("INGESTION_FILE_TOO_LARGE"));
        assertEquals(0, count("ingestion_file"));
    }

    @Test
    void anUnsupportedContentTypeIsRefused() throws Exception {
        HttpResponse<String> response = post("/statements", "application/pdf", new byte[] {1, 2, 3});

        assertEquals(415, response.statusCode());
        assertTrue(response.body().contains("INGESTION_UNSUPPORTED_MEDIA_TYPE"));
        assertEquals(0, count("ingestion_file"));
    }

    @Test
    void anUnknownIngestionIsNotFound() throws Exception {
        HttpResponse<String> response = get("/api/v1/ingestions/999999");

        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("INGESTION_NOT_FOUND"));
    }
    @Test
    void ledgerJsonIsAcceptedAndItsCanonicalFormDecidesDuplicates() throws Exception {
        String compact = "[{\"account\":\"ACC-1\",\"value_date\":\"2026-10-09\",\"amount\":\"10.00\","
                + "\"currency\":\"GBP\",\"reference\":\"REF-1\",\"counterparty\":\"Acme Ltd\","
                + "\"narrative\":\"payment\"}]";
        String reordered = "[ {\n \"narrative\": \"payment\", \"counterparty\": \"Acme Ltd\","
                + " \"reference\": \"REF-1\",\n \"currency\": \"GBP\", \"amount\": \"10.00\","
                + " \"booking_date\": null,\n \"value_date\": \"2026-10-09\", \"account\": \"ACC-1\" } ]";

        HttpResponse<String> first = post("/ledger", "application/json", compact.getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> second = post("/ledger", "application/json", reordered.getBytes(StandardCharsets.UTF_8));

        assertEquals(201, first.statusCode());
        assertEquals(200, second.statusCode());
        assertTrue(second.body().contains("\"duplicate\":true"));
        assertEquals(1, count("ingestion_file"));
        assertEquals(1, count("ledger_entry"));
    }

    @Test
    void aNumericAmountInLedgerJsonIsRefusedAndNothingIsStored() throws Exception {
        String json = "[{\"account\":\"ACC-1\",\"amount\":10.00}]";

        HttpResponse<String> response = post("/ledger", "application/json", json.getBytes(StandardCharsets.UTF_8));

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("INGESTION_MALFORMED_JSON"));
        assertEquals(0, count("ingestion_file"));
    }

    @Test
    void theSameEntryAsCsvAndAsJsonProducesIdenticalCanonicalRows() throws Exception {
        String csv = String.join("\n", HEADER, "ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd,payment");
        String json = "[{\"account\":\"ACC-1\",\"value_date\":\"2026-10-09\",\"amount\":\"10.00\","
                + "\"currency\":\"GBP\",\"reference\":\"REF-1\",\"counterparty\":\"Acme Ltd\","
                + "\"narrative\":\"payment\"}]";

        assertEquals(201, post("/ledger", "text/csv", csv.getBytes(StandardCharsets.UTF_8)).statusCode());
        assertEquals(201, post("/ledger", "application/json", json.getBytes(StandardCharsets.UTF_8)).statusCode());

        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT account, amount_minor, currency, value_date, booking_date, reference, reference_norm,"
                        + " counterparty, counterparty_norm, narrative FROM ledger_entry ORDER BY seq");
        assertEquals(2, rows.size());
        assertEquals(rows.get(0), rows.get(1));
    }
    @Test
    void aReusedEntryIdWithDifferentContentIsRejectedAsAConflict() throws Exception {
        post("/ledger", "text/csv", entryCsv("ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd,payment,L-1"));

        HttpResponse<String> second = post("/ledger", "text/csv", entryCsv(
                "ACC-1,,2026-10-09,99.00,GBP,REF-1,Acme Ltd,payment,L-1",
                "ACC-1,,2026-10-09,5.00,GBP,REF-2,Acme Ltd,payment,L-2"));

        assertEquals(201, second.statusCode());
        assertTrue(second.body().contains("\"acceptedCount\":1"));
        assertTrue(second.body().contains("\"rejectedCount\":1"));
        HttpResponse<String> detail = get(second.headers().firstValue("Location").orElseThrow());
        assertTrue(detail.body().contains("LEDGER_ENTRY_CONFLICT"));
        assertEquals(2, count("ledger_entry"));
        assertEquals(1000L,
                jdbc.queryForObject("SELECT amount_minor FROM ledger_entry WHERE entry_id = 'L-1'", Long.class));
    }

    @Test
    void aReusedEntryIdWithIdenticalContentIsRejectedAsADuplicateEntry() throws Exception {
        String same = "ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd,payment,L-1";
        post("/ledger", "text/csv", entryCsv(same));

        HttpResponse<String> second = post("/ledger", "text/csv",
                entryCsv(same, "ACC-1,,2026-10-09,5.00,GBP,REF-2,Acme Ltd,payment,L-2"));

        HttpResponse<String> detail = get(second.headers().firstValue("Location").orElseThrow());
        assertTrue(detail.body().contains("DUPLICATE_LEDGER_ENTRY"));
        assertEquals(2, count("ledger_entry"));
    }

    @Test
    void repeatedEntryIdsInsideOneFileKeepOnlyTheFirst() throws Exception {
        HttpResponse<String> response = post("/ledger", "text/csv", entryCsv(
                "ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd,payment,L-1",
                "ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd,payment,L-1",
                "ACC-1,,2026-10-09,99.00,GBP,REF-1,Acme Ltd,payment,L-1"));

        assertTrue(response.body().contains("\"acceptedCount\":1"));
        assertTrue(response.body().contains("\"rejectedCount\":2"));
        HttpResponse<String> detail = get(response.headers().firstValue("Location").orElseThrow());
        assertTrue(detail.body().contains("DUPLICATE_LEDGER_ENTRY"));
        assertTrue(detail.body().contains("LEDGER_ENTRY_CONFLICT"));
        assertEquals(1, count("ledger_entry"));
    }

    @Test
    void sixDifferentFilesCarryingTheSameEntryIdStoreItExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                final String narrative = "attempt-" + i;
                results.add(pool.submit(() -> post("/ledger", "text/csv", entryCsv(
                        "ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd," + narrative + ",L-9")).statusCode()));
            }
            for (Future<Integer> result : results) {
                assertEquals(201, result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(6, count("ingestion_file"));
        assertEquals(1, count("ledger_entry"));
        assertEquals(5, count("rejected_line"));
    }

    // ---- helpers ----

    private static HttpResponse<String> post(String path, String contentType, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v1/ingestions" + path))
                .header("Content-Type", contentType)
                .POST(BodyPublishers.ofByteArray(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int count(String table) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
    }
    private static byte[] entryCsv(String... rows) {
        return (HEADER + ",entry_id\n" + String.join("\n", rows)).getBytes(StandardCharsets.UTF_8);
    }
}