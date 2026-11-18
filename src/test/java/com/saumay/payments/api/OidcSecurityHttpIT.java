package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.saumay.payments.PaymentsApplication;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Exercises the actual OIDC filter chain over HTTP using a local JWK endpoint,
 * generated RSA keys and PostgreSQL. All data and keys are synthetic.
 */
class OidcSecurityHttpIT {

    private static final String ISSUER = "https://issuer.example.test";
    private static final String AUDIENCE = "payments-api";
    private static final String KEY_ID = "oidc-http-test-key";

    private static final String CSV_HEADER =
            "account,booking_date,value_date,amount,currency,reference,counterparty,narrative";

    private static PostgreSQLContainer postgres;
    private static HttpServer jwkServer;
    private static ConfigurableApplicationContext context;
    private static KeyPair trustedKey;
    private static KeyPair otherKey;
    private static HttpClient http;
    private static JdbcTemplate jdbc;
    private static String base;

    @BeforeAll
    static void start() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        trustedKey = generator.generateKeyPair();
        otherKey = generator.generateKeyPair();

        RSAKey publicJwk = new RSAKey.Builder((RSAPublicKey) trustedKey.getPublic())
                .keyID(KEY_ID)
                .build();

        byte[] jwks = new JWKSet(publicJwk).toString()
                .getBytes(StandardCharsets.UTF_8);

        jwkServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);

        jwkServer.createContext("/jwks", exchange -> {
            try {
                exchange.getResponseHeaders().set(
                        "Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(jwks);
                }
            } finally {
                exchange.close();
            }
        });

        jwkServer.start();

        postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();

        String jwkSetUri = "http://127.0.0.1:"
                + jwkServer.getAddress().getPort() + "/jwks";

        context = SpringApplication.run(PaymentsApplication.class,
                "--server.port=0",
                "--spring.profiles.active=oidc",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--payments.security.allow-insecure-loopback-jwks=true",
                "--payments.security.issuer=" + ISSUER,
                "--payments.security.audience=" + AUDIENCE,
                "--payments.security.jwk-set-uri=" + jwkSetUri);

        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        http = HttpClient.newHttpClient();

        base = "http://127.0.0.1:"
                + context.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (jwkServer != null) {
            jwkServer.stop(0);
        }
    }

    @Test
    void missingAndInvalidTokensAreRejected() throws Exception {
        assertEquals(401, request(
                "GET", "/api/v1/exceptions", null, null, null, null).statusCode());

        assertEquals(401, request(
                "GET", "/api/v1/exceptions", "not-a-jwt",
                null, null, null).statusCode());

        String wrongKey = token(
                otherKey, ISSUER, AUDIENCE, "alice",
                List.of("ANALYST"), Instant.now().plusSeconds(600));

        assertEquals(401, request(
                "GET", "/api/v1/exceptions", wrongKey,
                null, null, null).statusCode());

        String wrongIssuer = token(
                trustedKey, "https://wrong.example.test", AUDIENCE, "alice",
                List.of("ANALYST"), Instant.now().plusSeconds(600));

        assertEquals(401, request(
                "GET", "/api/v1/exceptions", wrongIssuer,
                null, null, null).statusCode());

        String wrongAudience = token(
                trustedKey, ISSUER, "another-api", "alice",
                List.of("ANALYST"), Instant.now().plusSeconds(600));

        assertEquals(401, request(
                "GET", "/api/v1/exceptions", wrongAudience,
                null, null, null).statusCode());

        String expired = token(
                trustedKey, ISSUER, AUDIENCE, "alice",
                List.of("ANALYST"), Instant.now().minusSeconds(3600));

        assertEquals(401, request(
                "GET", "/api/v1/exceptions", expired,
                null, null, null).statusCode());
    }

    @Test
    void invalidRequiredClaimsCannotWriteIngestionData() throws Exception {
        Instant now = Instant.now();

        JWTClaimsSet futureNotBefore = new JWTClaimsSet.Builder()
                .issueTime(Date.from(Instant.now()))
                .jwtID(java.util.UUID.randomUUID().toString())
                .claim("client_id", "integration-client")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("claim-test-admin")
                .claim("roles", List.of("ADMIN"))
                .notBeforeTime(Date.from(now.plusSeconds(600)))
                .expirationTime(Date.from(now.plusSeconds(1200)))
                .build();

        JWTClaimsSet missingExpiry = new JWTClaimsSet.Builder()
                .issueTime(Date.from(Instant.now()))
                .jwtID(java.util.UUID.randomUUID().toString())
                .claim("client_id", "integration-client")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("claim-test-admin")
                .claim("roles", List.of("ADMIN"))
                .build();

        JWTClaimsSet missingSubject = new JWTClaimsSet.Builder()
                .issueTime(Date.from(Instant.now()))
                .jwtID(java.util.UUID.randomUUID().toString())
                .claim("client_id", "integration-client")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .claim("roles", List.of("ADMIN"))
                .expirationTime(Date.from(now.plusSeconds(1200)))
                .build();

        JWTClaimsSet missingAudience = new JWTClaimsSet.Builder()
                .issueTime(Date.from(Instant.now()))
                .jwtID(java.util.UUID.randomUUID().toString())
                .claim("client_id", "integration-client")
                .issuer(ISSUER)
                .subject("claim-test-admin")
                .claim("roles", List.of("ADMIN"))
                .expirationTime(Date.from(now.plusSeconds(1200)))
                .build();

        List<JWTClaimsSet> invalidClaims = List.of(
                futureNotBefore, missingExpiry, missingSubject, missingAudience);

        Integer before = jdbc.queryForObject(
                "SELECT count(*) FROM ingestion_file", Integer.class);

        String statement = String.join("\n",
                CSV_HEADER,
                "ACC-CLAIM,,2026-10-09,10.00,GBP,CLAIM-TEST,Example,payment");

        for (JWTClaimsSet claims : invalidClaims) {
            HttpResponse<String> response = request(
                    "POST", "/api/v1/ingestions/statements",
                    signClaims(claims), null, "text/csv", statement);

            assertEquals(401, response.statusCode(), response.body());
        }

        assertEquals(before, jdbc.queryForObject(
                "SELECT count(*) FROM ingestion_file", Integer.class));
    }

    @Test
    void jwkRedirectIsNotFollowedAndItsBodyIsNotAccepted() throws Exception {
        java.util.concurrent.atomic.AtomicInteger redirectHits =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger targetHits =
                new java.util.concurrent.atomic.AtomicInteger();

        RSAKey publicJwk = new RSAKey.Builder((RSAPublicKey) trustedKey.getPublic())
                .keyID(KEY_ID)
                .build();
        byte[] validJwks = new JWKSet(publicJwk).toString()
                .getBytes(StandardCharsets.UTF_8);

        HttpServer redirectServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);

        try {
            redirectServer.createContext("/redirect", exchange -> {
                try {
                    redirectHits.incrementAndGet();
                    exchange.getResponseHeaders().set(
                            "Location",
                            "http://127.0.0.1:" + redirectServer.getAddress().getPort() + "/target");
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(302, validJwks.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(validJwks);
                    }
                } finally {
                    exchange.close();
                }
            });

            redirectServer.createContext("/target", exchange -> {
                try {
                    targetHits.incrementAndGet();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, validJwks.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(validJwks);
                    }
                } finally {
                    exchange.close();
                }
            });

            redirectServer.start();

            try (ConfigurableApplicationContext redirectedContext = SpringApplication.run(
                    PaymentsApplication.class,
                    "--server.port=0",
                    "--spring.profiles.active=oidc",
                    "--payments.security.allow-insecure-loopback-jwks=true",
                    "--payments.security.issuer=" + ISSUER,
                    "--payments.security.audience=" + AUDIENCE,
                    "--payments.security.jwk-set-uri=http://127.0.0.1:"
                            + redirectServer.getAddress().getPort() + "/redirect",
                    "--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword())) {

                String redirectedBase = "http://127.0.0.1:"
                        + redirectedContext.getEnvironment().getProperty("local.server.port");
                String bearer = token(
                        trustedKey, ISSUER, AUDIENCE, "redirect-test-admin",
                        List.of("ADMIN"), Instant.now().plusSeconds(600));

                HttpRequest request = HttpRequest.newBuilder(
                                URI.create(redirectedBase + "/api/v1/exceptions"))
                        .header("Authorization", "Bearer " + bearer)
                        .GET()
                        .build();

                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString());

                assertEquals(401, response.statusCode(), response.body());
                assertTrue(redirectHits.get() > 0, "The configured JWK endpoint must be contacted");
                assertEquals(0, targetHits.get(), "The redirect target must not be contacted");
            }
        } finally {
            redirectServer.stop(0);
        }
    }

    @Test
    void jwkEndpointFailureDoesNotAuthenticateAndAHealthyEndpointRecovers() throws Exception {
        java.util.concurrent.atomic.AtomicInteger mode =
                new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger fetches =
                new java.util.concurrent.atomic.AtomicInteger();

        RSAKey publicJwk = new RSAKey.Builder((RSAPublicKey) trustedKey.getPublic())
                .keyID(KEY_ID)
                .build();
        byte[] validJwks = new JWKSet(publicJwk).toString()
                .getBytes(StandardCharsets.UTF_8);
        byte[] unavailable = "temporarily unavailable".getBytes(StandardCharsets.UTF_8);
        byte[] malformed = "{not-json".getBytes(StandardCharsets.UTF_8);

        HttpServer changingServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);

        try {
            changingServer.createContext("/jwks", exchange -> {
                try {
                    fetches.incrementAndGet();
                    int currentMode = mode.get();
                    byte[] body = currentMode == 0
                            ? unavailable
                            : currentMode == 1 ? malformed : validJwks;
                    int status = currentMode == 0 ? 503 : 200;

                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);

                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                } finally {
                    exchange.close();
                }
            });
            changingServer.start();

            try (ConfigurableApplicationContext changingContext = SpringApplication.run(
                    PaymentsApplication.class,
                    "--server.port=0",
                    "--spring.profiles.active=oidc",
                    "--payments.security.allow-insecure-loopback-jwks=true",
                    "--payments.security.issuer=" + ISSUER,
                    "--payments.security.audience=" + AUDIENCE,
                    "--payments.security.jwk-set-uri=http://127.0.0.1:"
                            + changingServer.getAddress().getPort() + "/jwks",
                    "--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword())) {

                String changingBase = "http://127.0.0.1:"
                        + changingContext.getEnvironment().getProperty("local.server.port");

                String bearer = token(
                        trustedKey, ISSUER, AUDIENCE, "jwk-recovery-admin",
                        List.of("ADMIN"), Instant.now().plusSeconds(600));

                HttpRequest request = HttpRequest.newBuilder(
                                URI.create(changingBase + "/api/v1/exceptions"))
                        .header("Authorization", "Bearer " + bearer)
                        .GET()
                        .build();

                HttpResponse<String> unavailableResponse =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                assertTrue(unavailableResponse.statusCode() >= 400,
                        unavailableResponse.body());
                assertTrue(!unavailableResponse.body().contains(bearer),
                        unavailableResponse.body());

                mode.set(1);

                HttpResponse<String> malformedResponse =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                assertTrue(malformedResponse.statusCode() >= 400,
                        malformedResponse.body());
                assertTrue(!malformedResponse.body().contains(bearer),
                        malformedResponse.body());

                mode.set(2);

                HttpResponse<String> recovered =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, recovered.statusCode(), recovered.body());
                assertTrue(fetches.get() >= 3,
                        "The decoder must fetch again after failed JWK responses");
            }
        } finally {
            changingServer.stop(0);
        }
    }

    @Test
    void aNewSigningKeyIsFetchedWhenItsKeyIdChanges() throws Exception {
        String firstKeyId = "rotation-key-one";
        String secondKeyId = "rotation-key-two";

        RSAKey firstJwk = new RSAKey.Builder((RSAPublicKey) trustedKey.getPublic())
                .keyID(firstKeyId)
                .build();
        RSAKey secondJwk = new RSAKey.Builder((RSAPublicKey) otherKey.getPublic())
                .keyID(secondKeyId)
                .build();

        java.util.concurrent.atomic.AtomicReference<byte[]> published =
                new java.util.concurrent.atomic.AtomicReference<>(
                        new JWKSet(firstJwk).toString().getBytes(StandardCharsets.UTF_8));
        java.util.concurrent.atomic.AtomicInteger fetches =
                new java.util.concurrent.atomic.AtomicInteger();

        HttpServer rotatingServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);

        try {
            rotatingServer.createContext("/jwks", exchange -> {
                try {
                    fetches.incrementAndGet();
                    byte[] body = published.get();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                } finally {
                    exchange.close();
                }
            });
            rotatingServer.start();

            try (ConfigurableApplicationContext rotatingContext = SpringApplication.run(
                    PaymentsApplication.class,
                    "--server.port=0",
                    "--spring.profiles.active=oidc",
                    "--payments.security.allow-insecure-loopback-jwks=true",
                    "--payments.security.issuer=" + ISSUER,
                    "--payments.security.audience=" + AUDIENCE,
                    "--payments.security.jwk-set-uri=http://127.0.0.1:"
                            + rotatingServer.getAddress().getPort() + "/jwks",
                    "--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword())) {

                String rotatingBase = "http://127.0.0.1:"
                        + rotatingContext.getEnvironment().getProperty("local.server.port");

                String firstToken = tokenWithKeyId(trustedKey, firstKeyId);
                HttpRequest firstRequest = HttpRequest.newBuilder(
                                URI.create(rotatingBase + "/api/v1/exceptions"))
                        .header("Authorization", "Bearer " + firstToken)
                        .GET()
                        .build();

                HttpResponse<String> first =
                        http.send(firstRequest, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, first.statusCode(), first.body());

                published.set(new JWKSet(secondJwk).toString()
                        .getBytes(StandardCharsets.UTF_8));

                String secondToken = tokenWithKeyId(otherKey, secondKeyId);
                HttpRequest secondRequest = HttpRequest.newBuilder(
                                URI.create(rotatingBase + "/api/v1/exceptions"))
                        .header("Authorization", "Bearer " + secondToken)
                        .GET()
                        .build();

                HttpResponse<String> second =
                        http.send(secondRequest, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, second.statusCode(), second.body());
                assertTrue(fetches.get() >= 2,
                        "A token with a new key ID must trigger another JWK fetch");
            }
        } finally {
            rotatingServer.stop(0);
        }
    }

    @Test
    void unsupportedSigningAlgorithmsCannotWriteIngestionData() throws Exception {
        Instant now = Instant.now();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issueTime(Date.from(Instant.now()))
                .jwtID(java.util.UUID.randomUUID().toString())
                .claim("client_id", "integration-client")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("algorithm-test-admin")
                .claim("roles", List.of("ADMIN"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .build();

        SignedJWT rs384 = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS384).type(new com.nimbusds.jose.JOSEObjectType("at+jwt"))
                        .keyID(KEY_ID)
                        .build(),
                claims);
        rs384.sign(new RSASSASigner((RSAPrivateKey) trustedKey.getPrivate()));

        SignedJWT hs256 = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).type(new com.nimbusds.jose.JOSEObjectType("at+jwt"))
                        .keyID(KEY_ID)
                        .build(),
                claims);
        hs256.sign(new com.nimbusds.jose.crypto.MACSigner(new byte[32]));

        String unsecured = new com.nimbusds.jwt.PlainJWT(claims).serialize();

        List<String> unsupported = List.of(
                rs384.serialize(), hs256.serialize(), unsecured);

        Integer before = jdbc.queryForObject(
                "SELECT count(*) FROM ingestion_file", Integer.class);

        String statement = String.join("\n",
                CSV_HEADER,
                "ACC-ALG,,2026-10-09,10.00,GBP,ALG-TEST,Example,payment");

        for (String bearer : unsupported) {
            HttpResponse<String> response = request(
                    "POST", "/api/v1/ingestions/statements",
                    bearer, null, "text/csv", statement);

            assertEquals(401, response.statusCode(), response.body());
            assertTrue(!response.body().contains(bearer), response.body());
        }

        assertEquals(before, jdbc.queryForObject(
                "SELECT count(*) FROM ingestion_file", Integer.class));
    }

    @Test
    void jwtTypeMustIdentifyAnAccessToken() throws Exception {
        Instant now = Instant.now();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("token-purpose-admin")
                .claim("roles", List.of("ADMIN"))
                .claim("client_id", "integration-client")
                .jwtID(java.util.UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .build();

        SignedJWT accessToken = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(KEY_ID)
                        .type(new com.nimbusds.jose.JOSEObjectType("at+jwt"))
                        .build(),
                claims);
        accessToken.sign(new RSASSASigner((RSAPrivateKey) trustedKey.getPrivate()));

        SignedJWT otherJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(KEY_ID)
                        .type(new com.nimbusds.jose.JOSEObjectType("JWT"))
                        .build(),
                claims);
        otherJwt.sign(new RSASSASigner((RSAPrivateKey) trustedKey.getPrivate()));

        HttpResponse<String> accepted = request(
                "GET", "/api/v1/exceptions", accessToken.serialize(),
                null, null, null);
        assertEquals(200, accepted.statusCode(), accepted.body());

        HttpResponse<String> rejected = request(
                "GET", "/api/v1/exceptions", otherJwt.serialize(),
                null, null, null);
        assertEquals(401, rejected.statusCode(), rejected.body());
        assertTrue(!rejected.body().contains(otherJwt.serialize()), rejected.body());
    }

    @Test
    void validRoleCanReadButCannotPerformAdminOperation() throws Exception {
        String analyst = token(
                trustedKey, ISSUER, AUDIENCE, "analyst-user",
                List.of("ANALYST"), Instant.now().plusSeconds(600));

        assertEquals(200, request(
                "GET", "/api/v1/exceptions", analyst,
                null, null, null).statusCode());

        assertEquals(403, request(
                "POST", "/api/v1/ingestions/statements", analyst,
                null, "text/csv", CSV_HEADER).statusCode());
    }

    @Test
    void unknownRoleAndUnlistedRouteAreDenied() throws Exception {
        String unknownRole = token(
                trustedKey, ISSUER, AUDIENCE, "user",
                List.of("UNKNOWN"), Instant.now().plusSeconds(600));

        assertEquals(401, request(
                "GET", "/api/v1/exceptions", unknownRole,
                null, null, null).statusCode());

        String admin = token(
                trustedKey, ISSUER, AUDIENCE, "admin-user",
                List.of("ADMIN"), Instant.now().plusSeconds(600));

        assertEquals(403, request(
                "GET", "/api/v1/not-a-real-route", admin,
                null, null, null).statusCode());
    }

    @Test
    void actuatorIsRestrictedToAdmin() throws Exception {
        String analyst = token(
                trustedKey, ISSUER, AUDIENCE, "analyst-user",
                List.of("ANALYST"), Instant.now().plusSeconds(600));

        String admin = token(
                trustedKey, ISSUER, AUDIENCE, "admin-user",
                List.of("ADMIN"), Instant.now().plusSeconds(600));

        assertEquals(403, request(
                "GET", "/actuator/health", analyst,
                null, null, null).statusCode());

        assertEquals(200, request(
                "GET", "/actuator/health", admin,
                null, null, null).statusCode());
    }

    @Test
    void spoofedActorHeaderDoesNotChangeStoredCommentAuthor() throws Exception {
        String admin = token(
                trustedKey, ISSUER, AUDIENCE, "admin-user",
                List.of("ADMIN"), Instant.now().plusSeconds(600));

        String analyst = token(
                trustedKey, ISSUER, AUDIENCE, "real-analyst",
                List.of("ANALYST"), Instant.now().plusSeconds(600));

        String statement = String.join("\n",
                CSV_HEADER,
                "ACC-1,,2026-10-09,30.00,GBP,ONLY-STATEMENT,Acme,payment");

        String ledger = String.join("\n",
                CSV_HEADER,
                "ACC-1,,2026-10-09,10.00,GBP,ONLY-LEDGER,Other,fee");

        HttpResponse<String> statementResponse = request(
                "POST", "/api/v1/ingestions/statements",
                admin, null, "text/csv", statement);
        assertEquals(201, statementResponse.statusCode(), statementResponse.body());

        HttpResponse<String> ledgerResponse = request(
                "POST", "/api/v1/ingestions/ledger",
                admin, null, "text/csv", ledger);
        assertEquals(201, ledgerResponse.statusCode(), ledgerResponse.body());

        long statementId = number(statementResponse.body(), "ingestionId");

        String runBody = "{\"businessDate\":\"2026-10-09\","
                + "\"statementIngestionIds\":[" + statementId + "]}";

        HttpResponse<String> runResponse = request(
                "POST", "/api/v1/reconciliations/runs",
                admin, null, "application/json", runBody);
        assertEquals(201, runResponse.statusCode(), runResponse.body());

        long runId = number(runResponse.body(), "id");

        HttpResponse<String> intakeResponse = request(
                "POST", "/api/v1/reconciliations/runs/" + runId + "/exceptions",
                admin, null, null, null);
        assertEquals(200, intakeResponse.statusCode(), intakeResponse.body());

        Long caseId = jdbc.queryForObject(
                "SELECT min(id) FROM exception_case WHERE origin_run_id = ?",
                Long.class, runId);
        assertTrue(caseId != null, "The run must produce an exception case");

        HttpResponse<String> comment = request(
                "POST", "/api/v1/exceptions/" + caseId + "/comments",
                analyst, "spoofed-admin", "application/json",
                "{\"body\":\"OIDC actor verification\"}");

        assertEquals(201, comment.statusCode(), comment.body());

        String storedAuthor = jdbc.queryForObject(
                "SELECT author FROM case_comment WHERE case_id = ?"
                        + " AND body = ?",
                String.class, caseId, "OIDC actor verification");

        assertEquals(
                OidcActorId.fromVerifiedClaims(ISSUER, "real-analyst"),
                storedAuthor);
        assertNotEquals("spoofed-admin", storedAuthor);
    }

    @Test
    void oidcApprovalUsesVerifiedMakerAndCheckerIdentity() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();

        String admin = token(
                trustedKey, ISSUER, AUDIENCE, "approval-admin",
                List.of("ADMIN"), Instant.now().plusSeconds(600));
        String lead = token(
                trustedKey, ISSUER, AUDIENCE, "approval-lead",
                List.of("TEAM_LEAD"), Instant.now().plusSeconds(600));
        String analyst = token(
                trustedKey, ISSUER, AUDIENCE, "approval-analyst",
                List.of("ANALYST"), Instant.now().plusSeconds(600));
        String maker = token(
                trustedKey, ISSUER, AUDIENCE, "approval-maker",
                List.of("CONTROLLER"), Instant.now().plusSeconds(600));
        String checker = token(
                trustedKey, ISSUER, AUDIENCE, "approval-checker",
                List.of("CONTROLLER"), Instant.now().plusSeconds(600));

        String statement = String.join("\n",
                CSV_HEADER,
                "ACC-1,,2026-10-09,20000.00,GBP,OIDC-" + suffix + ",Acme,payment",
                "ACC-1,,2026-10-09,21000.00,GBP,LEGACY-" + suffix + ",Acme,payment");
        String ledger = String.join("\n",
                CSV_HEADER,
                "ACC-1,,2026-10-09,1.00,GBP,OTHER-" + suffix + ",Other,fee");

        HttpResponse<String> statements = request(
                "POST", "/api/v1/ingestions/statements",
                admin, null, "text/csv", statement);
        assertEquals(201, statements.statusCode(), statements.body());

        HttpResponse<String> ledgerResponse = request(
                "POST", "/api/v1/ingestions/ledger",
                admin, null, "text/csv", ledger);
        assertEquals(201, ledgerResponse.statusCode(), ledgerResponse.body());

        long statementId = number(statements.body(), "ingestionId");
        String runBody = "{\"businessDate\":\"2026-10-09\","
                + "\"statementIngestionIds\":[" + statementId + "]}";

        HttpResponse<String> run = request(
                "POST", "/api/v1/reconciliations/runs",
                admin, null, "application/json", runBody);
        assertEquals(201, run.statusCode(), run.body());

        long runId = number(run.body(), "id");
        HttpResponse<String> intake = request(
                "POST", "/api/v1/reconciliations/runs/" + runId + "/exceptions",
                admin, null, null, null);
        assertEquals(200, intake.statusCode(), intake.body());

        Long caseId = jdbc.queryForObject(
                "SELECT id FROM exception_case"
                        + " WHERE origin_run_id = ?"
                        + " AND kind = 'MISSING_LEDGER' AND amount_minor = ?",
                Long.class, runId, 2_000_000L);
        assertTrue(caseId != null, "The run must create the OIDC test case");

        Long legacyCaseId = jdbc.queryForObject(
                "SELECT id FROM exception_case"
                        + " WHERE origin_run_id = ?"
                        + " AND kind = 'MISSING_LEDGER' AND amount_minor = ?",
                Long.class, runId, 2_100_000L);
        assertTrue(legacyCaseId != null, "The run must create the legacy test case");

        long version = jdbc.queryForObject(
                "SELECT version FROM exception_case WHERE id = ?",
                Long.class, caseId);

        HttpResponse<String> assigned = request(
                "POST", "/api/v1/exceptions/" + caseId + "/assign",
                lead, "spoofed-actor", "application/json",
                "{\"expectedVersion\":" + version
                        + ",\"owner\":\"review-team\",\"reason\":\"review\"}");
        assertEquals(200, assigned.statusCode(), assigned.body());

        version = jdbc.queryForObject(
                "SELECT version FROM exception_case WHERE id = ?",
                Long.class, caseId);

        HttpResponse<String> investigating = request(
                "POST", "/api/v1/exceptions/" + caseId + "/investigate",
                analyst, "spoofed-actor", "application/json",
                "{\"expectedVersion\":" + version + "}");
        assertEquals(200, investigating.statusCode(), investigating.body());

        version = jdbc.queryForObject(
                "SELECT version FROM exception_case WHERE id = ?",
                Long.class, caseId);

        HttpResponse<String> submitted = request(
                "POST", "/api/v1/exceptions/" + caseId + "/resolve",
                maker, "spoofed-maker", "application/json",
                "{\"expectedVersion\":" + version
                        + ",\"action\":\"WRITE_OFF\",\"reason\":\"reviewed item\"}");
        assertEquals(202, submitted.statusCode(), submitted.body());

        long approvalId = number(submitted.body(), "id");
        String makerId = OidcActorId.fromVerifiedClaims(ISSUER, "approval-maker");
        String checkerId = OidcActorId.fromVerifiedClaims(ISSUER, "approval-checker");

        assertEquals(makerId, jdbc.queryForObject(
                "SELECT maker FROM approval_request WHERE id = ?",
                String.class, approvalId));
        assertEquals("OIDC", jdbc.queryForObject(
                "SELECT maker_identity_scheme FROM approval_request WHERE id = ?",
                String.class, approvalId));
        assertEquals(ISSUER, jdbc.queryForObject(
                "SELECT maker_issuer FROM approval_request WHERE id = ?",
                String.class, approvalId));

        HttpResponse<String> selfApproval = request(
                "POST", "/api/v1/approvals/" + approvalId + "/approve",
                maker, checkerId, "application/json", "{}");
        assertEquals(403, selfApproval.statusCode(), selfApproval.body());
        assertTrue(selfApproval.body().contains("SEPARATION_OF_DUTIES_VIOLATION"),
                selfApproval.body());

        HttpResponse<String> selfRejection = request(
                "POST", "/api/v1/approvals/" + approvalId + "/reject",
                maker, checkerId, "application/json", "{\"note\":\"no\"}");
        assertEquals(403, selfRejection.statusCode(), selfRejection.body());
        assertTrue(selfRejection.body().contains("SEPARATION_OF_DUTIES_VIOLATION"),
                selfRejection.body());

        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM approval_request WHERE id = ?",
                String.class, approvalId));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM audit_event"
                        + " WHERE entity_type = 'approval_request'"
                        + " AND entity_id = ? AND action = 'APPROVAL_DENIED'",
                Integer.class, Long.toString(approvalId)));

        HttpResponse<String> forbiddenRole = request(
                "POST", "/api/v1/approvals/" + approvalId + "/approve",
                analyst, null, "application/json", "{}");
        assertEquals(403, forbiddenRole.statusCode(), forbiddenRole.body());

        HttpResponse<String> approved = request(
                "POST", "/api/v1/approvals/" + approvalId + "/approve",
                checker, makerId, "application/json", "{\"note\":\"evidence seen\"}");
        assertEquals(200, approved.statusCode(), approved.body());

        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT status FROM approval_request WHERE id = ?",
                String.class, approvalId));
        assertEquals(checkerId, jdbc.queryForObject(
                "SELECT checker FROM approval_request WHERE id = ?",
                String.class, approvalId));
        assertEquals("RESOLVED", jdbc.queryForObject(
                "SELECT status FROM exception_case WHERE id = ?",
                String.class, caseId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM line_claim"
                        + " WHERE case_id = ? AND source = 'WRITE_OFF' AND action = 'CLAIM'",
                Integer.class, caseId));

        Long legacyApprovalId = jdbc.queryForObject(
                "INSERT INTO approval_request"
                        + " (case_id, action, amount_minor, currency, reason, maker)"
                        + " VALUES (?, 'WRITE_OFF', 2100000, 'GBP', 'legacy request', 'legacy-maker')"
                        + " RETURNING id",
                Long.class, legacyCaseId);
        assertTrue(legacyApprovalId != null);

        HttpResponse<String> legacyDecision = request(
                "POST", "/api/v1/approvals/" + legacyApprovalId + "/approve",
                checker, null, "application/json", "{}");
        assertEquals(409, legacyDecision.statusCode(), legacyDecision.body());
        assertTrue(legacyDecision.body().contains("IDENTITY_MIGRATION_REQUIRED"),
                legacyDecision.body());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM approval_request WHERE id = ?",
                String.class, legacyApprovalId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM audit_event"
                        + " WHERE entity_type = 'approval_request'"
                        + " AND entity_id = ?"
                        + " AND action = 'APPROVAL_IDENTITY_MISMATCH'"
                        + " AND actor = ?",
                Integer.class, Long.toString(legacyApprovalId), checkerId));

        HttpResponse<String> legacyRejection = request(
                "POST", "/api/v1/approvals/" + legacyApprovalId + "/reject",
                checker, null, "application/json", "{\"note\":\"cannot verify identity\"}");
        assertEquals(409, legacyRejection.statusCode(), legacyRejection.body());
        assertTrue(legacyRejection.body().contains("IDENTITY_MIGRATION_REQUIRED"),
                legacyRejection.body());

        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM audit_event"
                        + " WHERE entity_type = 'approval_request'"
                        + " AND entity_id = ?"
                        + " AND action = 'APPROVAL_IDENTITY_MISMATCH'"
                        + " AND actor = ?"
                        + " AND after_state::jsonb ->> 'denialReason' = 'IDENTITY_MIGRATION_REQUIRED'"
                        + " AND after_state::jsonb ->> 'deniedBy' = ?",
                Integer.class, Long.toString(legacyApprovalId), checkerId, checkerId));

        // Force the database audit insert to fail. The approval decision must
        // remain blocked, and the two previously committed events must remain.
        jdbc.execute("CREATE FUNCTION test_fail_identity_audit() RETURNS trigger"
                + " LANGUAGE plpgsql AS $$ BEGIN"
                + " RAISE EXCEPTION 'simulated identity audit failure';"
                + " END $$");

        try {
            jdbc.execute("CREATE TRIGGER trg_test_fail_identity_audit"
                    + " BEFORE INSERT ON audit_event"
                    + " FOR EACH ROW"
                    + " WHEN (NEW.action = 'APPROVAL_IDENTITY_MISMATCH')"
                    + " EXECUTE FUNCTION test_fail_identity_audit()");

            HttpResponse<String> auditFailure = request(
                    "POST", "/api/v1/approvals/" + legacyApprovalId + "/approve",
                    checker, null, "application/json", "{}");
            assertEquals(500, auditFailure.statusCode(), auditFailure.body());

            assertEquals("PENDING", jdbc.queryForObject(
                    "SELECT status FROM approval_request WHERE id = ?",
                    String.class, legacyApprovalId));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT count(*) FROM audit_event"
                            + " WHERE entity_type = 'approval_request'"
                            + " AND entity_id = ?"
                            + " AND action = 'APPROVAL_IDENTITY_MISMATCH'",
                    Integer.class, Long.toString(legacyApprovalId)));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM line_claim WHERE case_id = ?",
                    Integer.class, legacyCaseId));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_test_fail_identity_audit ON audit_event");
            jdbc.execute("DROP FUNCTION IF EXISTS test_fail_identity_audit()");
        }

        HttpResponse<String> auditVerification = request(
                "GET", "/api/v1/audit/verify", admin,
                null, null, null);
        assertEquals(200, auditVerification.statusCode(), auditVerification.body());
        assertTrue(auditVerification.body().contains("\"status\":\"OK\""),
                auditVerification.body());
    }
    @Test
    void everyListedEndpointEnforcesItsRoleMatrix() throws Exception {
        List<String> allRoles = List.of(
                "ADMIN", "ANALYST", "TEAM_LEAD", "CONTROLLER", "AUDITOR");

        java.util.Map<String, String> tokens = new java.util.HashMap<>();
        for (String role : allRoles) {
            tokens.put(role, token(
                    trustedKey, ISSUER, AUDIENCE, "matrix-" + role,
                    List.of(role), Instant.now().plusSeconds(600)));
        }

        List<AccessRule> rules = List.of(
                rule("POST", "/api/v1/ingestions/statements", "ADMIN"),
                rule("POST", "/api/v1/ingestions/ledger", "ADMIN"),
                rule("GET", "/api/v1/ingestions/999999", "ADMIN", "ANALYST", "AUDITOR"),

                rule("POST", "/api/v1/reconciliations/runs", "ADMIN"),
                rule("GET", "/api/v1/reconciliations/runs/999999",
                        "ADMIN", "ANALYST", "AUDITOR"),
                rule("GET", "/api/v1/reconciliations/runs/999999/results",
                        "ADMIN", "ANALYST", "AUDITOR"),
                rule("POST", "/api/v1/reconciliations/runs/999999/replay",
                        "ADMIN", "AUDITOR"),
                rule("POST", "/api/v1/reconciliations/runs/999999/exceptions",
                        "ADMIN", "TEAM_LEAD"),
                rule("POST", "/api/v1/reconciliations/runs/999999/matches/1/unmatch",
                        "ADMIN", "TEAM_LEAD"),

                rule("GET", "/api/v1/exceptions",
                        "ADMIN", "ANALYST", "TEAM_LEAD", "CONTROLLER", "AUDITOR"),
                rule("GET", "/api/v1/exceptions/999999",
                        "ADMIN", "ANALYST", "TEAM_LEAD", "CONTROLLER", "AUDITOR"),
                rule("POST", "/api/v1/exceptions/999999/assign",
                        "ADMIN", "TEAM_LEAD"),
                rule("POST", "/api/v1/exceptions/999999/investigate",
                        "ADMIN", "ANALYST", "TEAM_LEAD"),
                rule("POST", "/api/v1/exceptions/999999/comments",
                        "ADMIN", "ANALYST", "TEAM_LEAD"),
                rule("POST", "/api/v1/exceptions/999999/escalate",
                        "ADMIN", "TEAM_LEAD"),
                rule("POST", "/api/v1/exceptions/999999/match",
                        "ADMIN", "ANALYST", "TEAM_LEAD"),
                rule("POST", "/api/v1/exceptions/999999/unmatch",
                        "ADMIN", "TEAM_LEAD"),
                rule("POST", "/api/v1/exceptions/999999/resolve",
                        "ANALYST", "TEAM_LEAD", "CONTROLLER"),

                rule("GET", "/api/v1/approvals/999999",
                        "ADMIN", "ANALYST", "TEAM_LEAD", "CONTROLLER", "AUDITOR"),
                rule("POST", "/api/v1/approvals/999999/approve", "CONTROLLER"),
                rule("POST", "/api/v1/approvals/999999/reject", "CONTROLLER"),

                rule("GET", "/api/v1/audit/verify", "ADMIN", "AUDITOR"),
                rule("GET", "/api/v1/audit/exception_case/999999", "ADMIN", "AUDITOR"),
                rule("GET", "/api/v1/reports/workload", "ADMIN", "TEAM_LEAD"),
                rule("GET", "/api/v1/reports/ageing", "ADMIN", "TEAM_LEAD", "AUDITOR"),
                rule("GET", "/api/v1/reports/resolution-time", "ADMIN", "TEAM_LEAD", "AUDITOR"),
                rule("GET", "/api/v1/reports/match/999999", "ADMIN", "TEAM_LEAD", "AUDITOR"),
                rule("PUT", "/api/v1/configuration", "ADMIN"),
                rule("GET", "/actuator/health", "ADMIN"));

        java.util.Map<?, ?> documentedPaths = documentedApiPaths();
        assertEveryDocumentedOperationCovered(documentedPaths, rules);
        assertEveryControllerMappingCovered(rules);

        for (AccessRule access : rules) {
            if (access.path().startsWith("/api/v1/")) {
                assertDocumentedRoles(access, documentedPaths);
            }

            HttpResponse<String> anonymous = matrixRequest(access, null);

            assertEquals(401, anonymous.statusCode(),
                    "Anonymous request: " + access.method() + " " + access.path());

            for (String role : allRoles) {
                HttpResponse<String> response = matrixRequest(access, tokens.get(role));

                String scenario = role + " " + access.method() + " " + access.path()
                        + " returned " + response.statusCode();

                if (access.roles().contains(role)) {
                    assertNotEquals(401, response.statusCode(), scenario);
                    assertNotEquals(403, response.statusCode(), scenario);
                    assertTrue(response.statusCode() < 500, scenario);
                } else {
                    assertEquals(403, response.statusCode(), scenario);
                }
            }
        }
    }

    @Test
    void unlistedMethodsAndPathsAreDenied() throws Exception {
        String admin = token(
                trustedKey, ISSUER, AUDIENCE, "matrix-admin",
                List.of("ADMIN"), Instant.now().plusSeconds(600));

        assertEquals(403, request(
                "DELETE", "/api/v1/exceptions/999999",
                admin, null, null, null).statusCode());

        assertEquals(403, request(
                "GET", "/api/v1/unlisted-route",
                admin, null, null, null).statusCode());
    }

    @Test
    void unlistedAuditReportAndActuatorRoutesAreDenied() throws Exception {
        String admin = token(
                trustedKey, ISSUER, AUDIENCE, "route-policy-admin",
                List.of("ADMIN"), Instant.now().plusSeconds(600));
        String auditor = token(
                trustedKey, ISSUER, AUDIENCE, "route-policy-auditor",
                List.of("AUDITOR"), Instant.now().plusSeconds(600));
        String lead = token(
                trustedKey, ISSUER, AUDIENCE, "route-policy-lead",
                List.of("TEAM_LEAD"), Instant.now().plusSeconds(600));

        HttpResponse<String> audit = request(
                "GET", "/api/v1/audit/unlisted", auditor,
                null, null, null);
        assertEquals(403, audit.statusCode(), audit.body());

        HttpResponse<String> report = request(
                "GET", "/api/v1/reports/unlisted", lead,
                null, null, null);
        assertEquals(403, report.statusCode(), report.body());

        HttpResponse<String> actuator = request(
                "GET", "/actuator/unlisted", admin,
                null, null, null);
        assertEquals(403, actuator.statusCode(), actuator.body());

        HttpResponse<String> wrongMethod = request(
                "PUT", "/actuator/health", admin,
                null, null, null);
        assertEquals(403, wrongMethod.statusCode(), wrongMethod.body());

        HttpResponse<String> allowedHealth = request(
                "GET", "/actuator/health", admin,
                null, null, null);
        assertEquals(200, allowedHealth.statusCode(), allowedHealth.body());
    }

    private static java.util.Map<?, ?> documentedApiPaths() throws Exception {
        try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(
                java.nio.file.Path.of("docs", "api", "openapi.yaml"))) {
            Object loaded = new org.yaml.snakeyaml.Yaml().load(reader);
            assertTrue(loaded instanceof java.util.Map<?, ?>,
                    "The OpenAPI document must be a mapping");

            Object paths = ((java.util.Map<?, ?>) loaded).get("paths");
            assertTrue(paths instanceof java.util.Map<?, ?>,
                    "The OpenAPI document must contain paths");

            return (java.util.Map<?, ?>) paths;
        }
    }

    private static void assertDocumentedRoles(
            AccessRule access, java.util.Map<?, ?> documentedPaths) {
        int matches = 0;
        String method = access.method().toLowerCase(java.util.Locale.ROOT);

        for (java.util.Map.Entry<?, ?> entry : documentedPaths.entrySet()) {
            if (!(entry.getKey() instanceof String template)
                    || !pathMatchesTemplate(template, access.path())
                    || !(entry.getValue() instanceof java.util.Map<?, ?> pathItem)) {
                continue;
            }

            Object operationValue = pathItem.get(method);
            if (!(operationValue instanceof java.util.Map<?, ?> operation)) {
                continue;
            }

            matches++;
            Object roleValue = operation.get("x-required-roles");
            String where = access.method() + " " + access.path();

            assertTrue(roleValue instanceof List<?>,
                    where + " must document its required roles");
            assertEquals(
                    java.util.Set.copyOf(access.roles()),
                    java.util.Set.copyOf((List<?>) roleValue),
                    where + " has different roles in OpenAPI and the HTTP test");
        }

        assertEquals(1, matches,
                access.method() + " " + access.path()
                        + " must match exactly one OpenAPI operation");
    }

    private static boolean pathMatchesTemplate(String template, String actual) {
        String[] templateSegments = template.split("/", -1);
        String[] actualSegments = actual.split("/", -1);

        if (templateSegments.length != actualSegments.length) {
            return false;
        }

        for (int i = 0; i < templateSegments.length; i++) {
            String segment = templateSegments[i];
            boolean parameter = segment.startsWith("{")
                    && segment.endsWith("}")
                    && segment.length() > 2;

            if (parameter) {
                if (actualSegments[i].isEmpty()) {
                    return false;
                }
            } else if (!segment.equals(actualSegments[i])) {
                return false;
            }
        }

        return true;
    }

    private static void assertEveryDocumentedOperationCovered(
            java.util.Map<?, ?> documentedPaths, List<AccessRule> rules) {
        java.util.Set<String> methods = java.util.Set.of(
                "get", "post", "put", "patch", "delete", "head", "options", "trace");

        for (java.util.Map.Entry<?, ?> entry : documentedPaths.entrySet()) {
            if (!(entry.getKey() instanceof String template)
                    || !template.startsWith("/api/v1/")
                    || !(entry.getValue() instanceof java.util.Map<?, ?> pathItem)) {
                continue;
            }

            for (Object key : pathItem.keySet()) {
                if (!(key instanceof String method) || !methods.contains(method)) {
                    continue;
                }

                long matches = rules.stream()
                        .filter(rule -> rule.method().equalsIgnoreCase(method))
                        .filter(rule -> pathMatchesTemplate(template, rule.path()))
                        .count();

                assertEquals(1L, matches,
                        method.toUpperCase(java.util.Locale.ROOT) + " " + template
                                + " must have exactly one HTTP matrix entry");
            }
        }
    }

    private static void assertEveryControllerMappingCovered(List<AccessRule> rules) {
        var mappings = context.getBean(
                "requestMappingHandlerMapping",
                org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);

        for (var mapping : mappings.getHandlerMethods().keySet()) {
            for (String template : mapping.getPatternValues()) {
                if (!template.startsWith("/api/v1/")) {
                    continue;
                }

                var methods = mapping.getMethodsCondition().getMethods();
                assertTrue(!methods.isEmpty(),
                        template + " must declare its HTTP methods explicitly");

                for (var method : methods) {
                    long matches = rules.stream()
                            .filter(rule -> rule.method().equals(method.name()))
                            .filter(rule -> pathMatchesTemplate(template, rule.path()))
                            .count();

                    assertEquals(1L, matches,
                            method.name() + " " + template
                                    + " must have exactly one HTTP matrix entry");
                }
            }
        }
    }

    private static HttpResponse<String> matrixRequest(
            AccessRule access, String bearer) throws Exception {

        if ("GET".equals(access.method())) {
            return request(access.method(), access.path(), bearer, null, null, null);
        }

        // Deliberately malformed JSON stops permitted handlers before a
        // business action while still allowing the security chain to decide.
        return request(access.method(), access.path(), bearer, null,
                "application/json", "{");
    }

    private static AccessRule rule(String method, String path, String... roles) {
        return new AccessRule(method, path, List.of(roles));
    }

    private record AccessRule(String method, String path, List<String> roles) {
    }
    @Test
    void securityErrorsUseTheApiErrorContract() throws Exception {
        String correlation = "security-test-123";

        HttpRequest missingToken = HttpRequest.newBuilder(
                        URI.create(base + "/api/v1/exceptions"))
                .header("X-Correlation-Id", correlation)
                .GET()
                .build();

        HttpResponse<String> unauthorized =
                http.send(missingToken, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, unauthorized.statusCode());
        assertTrue(unauthorized.headers()
                .firstValue("Content-Type").orElse("")
                .startsWith("application/json"));
        assertEquals("Bearer", unauthorized.headers()
                .firstValue("WWW-Authenticate").orElse(""));
        assertTrue(unauthorized.body().contains(
                "\"code\":\"AUTHENTICATION_REQUIRED\""), unauthorized.body());
        assertTrue(unauthorized.body().contains(
                "\"correlationId\":\"" + correlation + "\""), unauthorized.body());
        assertTrue(unauthorized.body().contains("\"timestamp\":"), unauthorized.body());

        String analyst = token(
                trustedKey, ISSUER, AUDIENCE, "analyst-errors",
                List.of("ANALYST"), Instant.now().plusSeconds(600));

        HttpRequest forbiddenRequest = HttpRequest.newBuilder(
                        URI.create(base + "/api/v1/ingestions/statements"))
                .header("Authorization", "Bearer " + analyst)
                .header("X-Correlation-Id", correlation)
                .header("Content-Type", "text/csv")
                .POST(BodyPublishers.ofString(CSV_HEADER))
                .build();

        HttpResponse<String> forbidden =
                http.send(forbiddenRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(403, forbidden.statusCode());
        assertTrue(forbidden.body().contains(
                "\"code\":\"ACCESS_DENIED\""), forbidden.body());
        assertTrue(forbidden.body().contains(
                "\"correlationId\":\"" + correlation + "\""), forbidden.body());
        assertTrue(!forbidden.body().contains(analyst), forbidden.body());

        String invalidToken = "not-a-valid-token";

        HttpRequest invalidRequest = HttpRequest.newBuilder(
                        URI.create(base + "/api/v1/exceptions"))
                .header("Authorization", "Bearer " + invalidToken)
                .header("X-Correlation-Id", correlation)
                .GET()
                .build();

        HttpResponse<String> invalid =
                http.send(invalidRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, invalid.statusCode());
        assertTrue(invalid.body().contains(
                "\"code\":\"AUTHENTICATION_REQUIRED\""), invalid.body());
        assertTrue(!invalid.body().contains(invalidToken), invalid.body());
    }
    private static HttpResponse<String> request(
            String method,
            String path,
            String bearer,
            String actor,
            String contentType,
            String body) throws Exception {

        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create(base + path));

        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (actor != null) {
            builder.header("X-Actor", actor);
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }

        builder.method(method, body == null
                ? BodyPublishers.noBody()
                : BodyPublishers.ofString(body));

        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String signClaims(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(new com.nimbusds.jose.JOSEObjectType("at+jwt"))
                        .keyID(KEY_ID)
                        .build(),
                claims);

        jwt.sign(new RSASSASigner((RSAPrivateKey) trustedKey.getPrivate()));
        return jwt.serialize();
    }

    private static String tokenWithKeyId(KeyPair key, String keyId) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issueTime(Date.from(Instant.now()))
                .jwtID(java.util.UUID.randomUUID().toString())
                .claim("client_id", "integration-client")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("rotation-test-admin")
                .claim("roles", List.of("ADMIN"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(new com.nimbusds.jose.JOSEObjectType("at+jwt"))
                        .keyID(keyId)
                        .build(),
                claims);

        jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
        return jwt.serialize();
    }

    private static String token(
            KeyPair key,
            String issuer,
            String audience,
            String subject,
            List<String> roles,
            Instant expiresAt) throws Exception {

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issueTime(Date.from(Instant.now()))
                .jwtID(java.util.UUID.randomUUID().toString())
                .claim("client_id", "integration-client")
                .issuer(issuer)
                .audience(audience)
                .subject(subject)
                .claim("roles", roles)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(expiresAt))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(new com.nimbusds.jose.JOSEObjectType("at+jwt"))
                        .keyID(KEY_ID)
                        .build(),
                claims);

        jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
        return jwt.serialize();
    }

    private static long number(String body, String field) {
        Matcher matcher = Pattern.compile(
                "\"" + Pattern.quote(field) + "\":\\s*(\\d+)")
                .matcher(body);

        assertTrue(matcher.find(), field + " not found in response: " + body);
        return Long.parseLong(Objects.requireNonNull(matcher.group(1)));
    }
}