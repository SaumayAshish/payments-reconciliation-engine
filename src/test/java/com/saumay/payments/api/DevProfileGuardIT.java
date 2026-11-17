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

    private static void assertDenied(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .GET()
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode(), path + ": " + response.body());
    }
}