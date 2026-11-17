package com.saumay.payments.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * AC-01.5: the process is killed in the middle of an ingestion. Nothing partial may remain, and after a restart the
 * same file must ingest completely. The application runs as a real child JVM and is killed with a hard kill while
 * its ingestion transaction is provably open (a test-only trigger stalls the insert of line 500).
 */
class ProcessKillRecoveryIT {

    private static final String APP_NAME = "recon-child";
    private static final String HEADER =
            "account,booking_date,value_date,amount,currency,reference,counterparty,narrative";
    private static final int LINES = 1000;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final String ISSUER = "https://recovery-issuer.example.test";
    private static final String AUDIENCE = "payments-recovery-test";
    private static final String KEY_ID = "recovery-test-key";

    private static PostgreSQLContainer postgres;
    private static JdbcTemplate jdbc;
    private static com.sun.net.httpserver.HttpServer jwkServer;
    private static String adminToken;

    private final List<Process> started = new ArrayList<>();

    @BeforeAll
    static void startDatabase() throws Exception {
        startTestIdentityProvider();

        postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
        if (jwkServer != null) {
            jwkServer.stop(0);
        }
    }

    private static void startTestIdentityProvider() throws Exception {
        java.security.KeyPairGenerator generator =
                java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair keyPair = generator.generateKeyPair();

        com.nimbusds.jose.jwk.RSAKey publicKey =
                new com.nimbusds.jose.jwk.RSAKey.Builder(
                        (java.security.interfaces.RSAPublicKey) keyPair.getPublic())
                        .keyID(KEY_ID)
                        .build();

        byte[] jwks = new com.nimbusds.jose.jwk.JWKSet(publicKey)
                .toString()
                .getBytes(StandardCharsets.UTF_8);

        jwkServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);

        jwkServer.createContext("/jwks", exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(jwks);
                }
            } finally {
                exchange.close();
            }
        });

        jwkServer.start();

        java.time.Instant now = java.time.Instant.now();
        com.nimbusds.jwt.JWTClaimsSet claims =
                new com.nimbusds.jwt.JWTClaimsSet.Builder()
                        .issuer(ISSUER)
                        .audience(AUDIENCE)
                        .subject("recovery-test-admin")
                        .claim("roles", List.of("ADMIN"))
                        .issueTime(java.util.Date.from(now))
                        .expirationTime(java.util.Date.from(now.plusSeconds(7200)))
                        .build();

        com.nimbusds.jwt.SignedJWT jwt = new com.nimbusds.jwt.SignedJWT(
                new com.nimbusds.jose.JWSHeader.Builder(
                        com.nimbusds.jose.JWSAlgorithm.RS256)
                        .keyID(KEY_ID)
                        .build(),
                claims);

        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(
                (java.security.interfaces.RSAPrivateKey) keyPair.getPrivate()));

        adminToken = jwt.serialize();
    }

    @AfterEach
    void killLeftoverChildren() {
        started.forEach(Process::destroyForcibly);
    }

    @Test
    void aProcessKilledMidIngestionLeavesNothingBehindAndARestartIngestsTheFileCleanly() throws Exception {
        byte[] file = statementCsv();

        // First process: migrates the schema, then starts an ingestion that stalls at line 500.
        RunningApp first = startApp();

        HttpRequest anonymous = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + first.port() + "/api/v1/ingestions/0"))
                .GET()
                .build();
        assertEquals(401, HTTP.send(anonymous, BodyHandlers.discarding()).statusCode());

        jdbc.execute("CREATE FUNCTION slow_insert() RETURNS trigger AS $$ BEGIN"
                + " IF NEW.line_number = 500 THEN PERFORM pg_sleep(5); END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER trg_slow_insert BEFORE INSERT ON statement_line"
                + " FOR EACH ROW EXECUTE FUNCTION slow_insert()");
        CompletableFuture<HttpResponse<String>> inFlight = upload(first, file);

        awaitTrue(() -> sleepingBackends() == 1, 30, "the ingestion should reach the stalled insert");
        assertEquals(0, count("ingestion_file"), "an open transaction must not be visible to other sessions");
        assertEquals(0, count("statement_line"), "an open transaction must not be visible to other sessions");

        // Hard kill while the transaction is open.
        first.process().destroyForcibly();
        first.process().waitFor();
        assertThrows(ExecutionException.class, () -> inFlight.get(30, TimeUnit.SECONDS),
                "the client must never receive a success answer from a process that was killed");
        awaitTrue(() -> appBackends() == 0, 60, "the database should drop the dead client's connections");

        assertEquals(0, count("ingestion_file"), "no ingestion row may survive the crash");
        assertEquals(0, count("statement_line"), "no partial lines may survive the crash");
        assertEquals(0, count("rejected_line"), "no partial rejected lines may survive the crash");

        // Restart: a fresh process ingests the same file completely.
        jdbc.execute("DROP TRIGGER trg_slow_insert ON statement_line");
        jdbc.execute("DROP FUNCTION slow_insert()");
        RunningApp second = startApp();

        HttpResponse<String> retry = upload(second, file).get(60, TimeUnit.SECONDS);
        assertEquals(201, retry.statusCode());
        assertTrue(retry.body().contains("\"acceptedCount\":" + LINES));
        assertTrue(retry.body().contains("\"duplicate\":false"));
        assertEquals(1, count("ingestion_file"));
        assertEquals(LINES, count("statement_line"));

        HttpResponse<String> again = upload(second, file).get(60, TimeUnit.SECONDS);
        assertEquals(200, again.statusCode());
        assertTrue(again.body().contains("\"duplicate\":true"));
        assertEquals(1, count("ingestion_file"));
        assertEquals(LINES, count("statement_line"));
    }

    // ---- child process handling ----

    private record RunningApp(Process process, int port, Path log) {
    }

    private RunningApp startApp() throws Exception {
        int port = freePort();
        Path log = Files.createTempFile("recon-child-", ".log");
        Path args = Files.createTempFile("recon-child-", ".args");
        String url = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                + "ApplicationName=" + APP_NAME;
        String classpath = System.getProperty("java.class.path").replace('\\', '/');
        Files.write(args, List.of(
                "-cp",
                quote(classpath),
                "com.saumay.payments.PaymentsApplication",
                quote("--spring.profiles.active=oidc"),
                quote("--payments.security.allow-insecure-loopback-jwks=true"),
                quote("--payments.security.issuer=" + ISSUER),
                quote("--payments.security.audience=" + AUDIENCE),
                quote("--payments.security.jwk-set-uri=http://127.0.0.1:"
                        + jwkServer.getAddress().getPort() + "/jwks"),
                quote("--server.port=" + port),
                quote("--spring.datasource.url=" + url),
                quote("--spring.datasource.username=" + postgres.getUsername()),
                quote("--spring.datasource.password=" + postgres.getPassword())));

        ProcessBuilder builder = new ProcessBuilder(javaBinary().toString(), "@" + args);
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        started.add(process);

        RunningApp app = new RunningApp(process, port, log);
        awaitTrue(() -> {
            if (!process.isAlive()) {
                throw new AssertionError("The child application exited early. Log tail:\n" + tail(log));
            }
            return answers(port);
        }, 120, "the child application should start answering HTTP requests. Log: " + log);
        return app;
    }

    private static Path javaBinary() {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean answers(int port) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/ingestions/0"))
                    .header("Authorization", "Bearer " + adminToken)
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<Void> response = HTTP.send(request, BodyHandlers.discarding());
            return response.statusCode() == 404;
        } catch (Exception e) {
            return false;
        }
    }

    private static String tail(Path log) {
        try {
            List<String> lines = Files.readAllLines(log);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
        } catch (IOException e) {
            return "(log unreadable)";
        }
    }

    // ---- helpers ----

    private static CompletableFuture<HttpResponse<String>> upload(RunningApp app, byte[] body) {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + app.port() + "/api/v1/ingestions/statements"))
                .header("Content-Type", "text/csv")
                .header("Authorization", "Bearer " + adminToken)
                .POST(BodyPublishers.ofByteArray(body))
                .build();
        return HTTP.sendAsync(request, BodyHandlers.ofString());
    }

    private static byte[] statementCsv() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= LINES; i++) {
            csv.append("\nACC-1,,2026-10-09,").append(i).append(".00,GBP,REF-").append(i).append(",Acme Ltd,payment");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int sleepingBackends() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND wait_event = 'PgSleep'",
                Integer.class, APP_NAME));
    }

    private static int appBackends() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE application_name = ?", Integer.class, APP_NAME));
    }

    private static int count(String table) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
    }

    private static void awaitTrue(BooleanSupplier condition, int seconds, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out after " + seconds + "s waiting: " + description);
    }
}