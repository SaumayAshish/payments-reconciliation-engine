package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.PaymentsApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The legacy dev profile is intentionally unsecured. Starting it requires
 * an explicit opt-in, even when test resources normally enable that opt-in.
 */
class DevProfileGuardIT {

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void devProfileWithoutExplicitOptInFailsToStart() {
        Exception failure = assertThrows(Exception.class, () -> {
            try (ConfigurableApplicationContext context = SpringApplication.run(
                    PaymentsApplication.class,
                    "--server.port=0",
                    "--spring.profiles.active=dev",
                    "--payments.security.allow-insecure-dev=false",
                    "--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword())) {
                // If startup succeeds, assertThrows fails. Referencing the
                // resource also avoids the compiler's unused-resource warning.
                context.getId();
            }
        });

        assertTrue(
                hasGuardMessage(failure),
                "Startup must fail because the insecure dev opt-in is absent: " + failure);
    }

    private static boolean hasGuardMessage(Throwable failure) {
        Throwable current = failure;

        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage().contains(
                    "The dev profile is unsecured and requires "
                            + "payments.security.allow-insecure-dev=true")) {
                return true;
            }
            current = current.getCause();
        }

        return false;
    }
}
