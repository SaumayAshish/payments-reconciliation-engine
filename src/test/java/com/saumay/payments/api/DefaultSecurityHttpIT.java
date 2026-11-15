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

/** Verifies HTTP access is denied without the oidc or dev profile. */
class DefaultSecurityHttpIT {

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
                "--spring.profiles.active=default",
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
    void apiAndActuatorRejectUnauthenticatedRequests() throws Exception {
        assertDenied("/api/v1/exceptions");
        assertDenied("/actuator/health");
        assertDenied("/api/v1/unlisted-route");
    }

    @Test
    void actorHeaderCannotBypassDefaultDeny() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(base + "/api/v1/exceptions"))
                .header("X-Actor", "root")
                .header("X-Correlation-Id", "default-test-123")
                .GET()
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode(), response.body());
        assertTrue(response.body().contains(
                "\"code\":\"AUTHENTICATION_REQUIRED\""), response.body());
        assertTrue(response.body().contains(
                "\"correlationId\":\"default-test-123\""), response.body());
    }

    private static void assertDenied(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(base + path))
                .GET()
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode(), path + ": " + response.body());
    }
}