package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.PaymentsApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The dev profile and the former compatibility flag must not enable
 * unauthenticated HTTP access.
 */
class DevProfileGuardIT {

    private static PostgreSQLContainer postgres;
    private static ConfigurableApplicationContext context;
    private static HttpClient http;
    private static String base;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();

        context = SpringApplication.run(PaymentsApplication.class,
                "--server.port=0",
                "--spring.profiles.active=dev",
                "--payments.security.allow-insecure-dev=true",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword());

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
    }

    @Test
    void devProfileAndFormerOptInStillDenyAnonymousRequests() throws Exception {
        assertDenied("/api/v1/exceptions");
        assertDenied("/actuator/health");
    }

    @Test
    void actorHeaderCannotBypassDevProfileDefaultDeny() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(base + "/api/v1/exceptions"))
                .header("X-Actor", "root")
                .GET()
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode(), response.body());
        assertTrue(response.body().contains("AUTHENTICATION_REQUIRED"), response.body());
    }

    @Test
    void oidcStillAuthenticatesWhenDevAndFormerOptInAreAlsoSet() throws Exception {
        try (TestOidcIssuer issuer = new TestOidcIssuer();
                ConfigurableApplicationContext oidcContext = SpringApplication.run(
                        PaymentsApplication.class,
                        "--server.port=0",
                        "--spring.profiles.active=dev,oidc",
                        "--payments.security.allow-insecure-dev=true",
                        "--payments.security.allow-insecure-loopback-jwks=true",
                        "--payments.security.issuer=" + TestOidcIssuer.ISSUER,
                        "--payments.security.audience=" + TestOidcIssuer.AUDIENCE,
                        "--payments.security.jwk-set-uri=" + issuer.jwkSetUri(),
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword())) {

            String oidcBase = "http://127.0.0.1:"
                    + oidcContext.getEnvironment().getProperty("local.server.port");
            String analystToken = issuer.token("combined-profile-analyst", "ANALYST");

            HttpRequest anonymous = HttpRequest.newBuilder(
                            URI.create(oidcBase + "/api/v1/exceptions"))
                    .GET()
                    .build();

            HttpRequest invalidToken = HttpRequest.newBuilder(
                            URI.create(oidcBase + "/api/v1/exceptions"))
                    .header("Authorization", "Bearer invalid-token")
                    .GET()
                    .build();

            HttpRequest analystRead = HttpRequest.newBuilder(
                            URI.create(oidcBase + "/api/v1/exceptions"))
                    .header("Authorization", "Bearer " + analystToken)
                    .GET()
                    .build();

            HttpRequest spoofedAdmin = HttpRequest.newBuilder(
                            URI.create(oidcBase + "/api/v1/ingestions/statements"))
                    .header("Authorization", "Bearer " + analystToken)
                    .header("X-Actor", "root")
                    .header("Content-Type", "text/csv")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "account,booking_date,value_date,amount,currency,reference,counterparty,narrative"))
                    .build();

            HttpResponse<String> anonymousResponse =
                    http.send(anonymous, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> invalidResponse =
                    http.send(invalidToken, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> readResponse =
                    http.send(analystRead, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> spoofedResponse =
                    http.send(spoofedAdmin, HttpResponse.BodyHandlers.ofString());

            assertEquals(401, anonymousResponse.statusCode(), anonymousResponse.body());
            assertEquals(401, invalidResponse.statusCode(), invalidResponse.body());
            assertEquals(200, readResponse.statusCode(), readResponse.body());
            assertEquals(403, spoofedResponse.statusCode(), spoofedResponse.body());
        }
    }

    @Test
    void oidcRejectsPlainHttpJwksWithoutExplicitOptIn() throws Exception {
        try (TestOidcIssuer issuer = new TestOidcIssuer()) {
            Exception failure = org.junit.jupiter.api.Assertions.assertThrows(
                    Exception.class,
                    () -> {
                        try (ConfigurableApplicationContext rejected = SpringApplication.run(
                                PaymentsApplication.class,
                                "--server.port=0",
                                "--spring.profiles.active=oidc",
                                "--payments.security.allow-insecure-loopback-jwks=false",
                                "--payments.security.issuer=" + TestOidcIssuer.ISSUER,
                                "--payments.security.audience=" + TestOidcIssuer.AUDIENCE,
                                "--payments.security.jwk-set-uri=" + issuer.jwkSetUri(),
                                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                                "--spring.datasource.username=" + postgres.getUsername(),
                                "--spring.datasource.password=" + postgres.getPassword())) {
                            rejected.getId();
                        }
                    });

            boolean foundPolicyError = false;
            Throwable current = failure;

            while (current != null) {
                if (current.getMessage() != null
                        && current.getMessage().contains("JWK set URI must use HTTPS")) {
                    foundPolicyError = true;
                    break;
                }
                current = current.getCause();
            }

            assertTrue(foundPolicyError,
                    "Startup must fail because the HTTP JWK endpoint was not explicitly allowed: " + failure);
        }
    }

    @Test
    void remoteHttpJwksIsRejectedEvenWithLoopbackOptIn() {
        Exception failure = org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class,
                () -> {
                    try (ConfigurableApplicationContext rejected = SpringApplication.run(
                            PaymentsApplication.class,
                            "--server.port=0",
                            "--spring.profiles.active=oidc",
                            "--payments.security.allow-insecure-loopback-jwks=true",
                            "--payments.security.issuer=https://identity.example.test",
                            "--payments.security.audience=payments-api",
                            "--payments.security.jwk-set-uri=http://192.0.2.1/jwks",
                            "--spring.datasource.url=" + postgres.getJdbcUrl(),
                            "--spring.datasource.username=" + postgres.getUsername(),
                            "--spring.datasource.password=" + postgres.getPassword())) {
                        rejected.getId();
                    }
                });

        boolean foundPolicyError = false;
        Throwable current = failure;

        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage().contains("JWK set URI must use HTTPS")) {
                foundPolicyError = true;
                break;
            }
            current = current.getCause();
        }

        assertTrue(foundPolicyError,
                "Startup must reject a remote HTTP JWK endpoint: " + failure);
    }

    private static void assertDenied(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .GET()
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode(), path + ": " + response.body());
    }
}