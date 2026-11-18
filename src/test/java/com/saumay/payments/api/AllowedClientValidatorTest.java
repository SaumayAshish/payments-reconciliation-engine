package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AllowedClientValidatorTest {

    @Test
    void acceptsOnlyConfiguredClientIds() {
        var validator = new AllowedClientValidator(
                "dashboard-client, operations-client");

        assertFalse(validator.validate(token("dashboard-client")).hasErrors());
        assertFalse(validator.validate(token("operations-client")).hasErrors());
        assertTrue(validator.validate(token("other-client")).hasErrors());
        assertTrue(validator.validate(token("Dashboard-client")).hasErrors());
        assertTrue(validator.validate(token(" dashboard-client")).hasErrors());
    }

    @Test
    void rejectsMissingOrNonStringClientId() {
        var validator = new AllowedClientValidator("dashboard-client");

        assertTrue(validator.validate(token(null)).hasErrors());
        assertTrue(validator.validate(token(42)).hasErrors());
    }

    @Test
    void rejectsEmptyOrMalformedConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AllowedClientValidator(""));

        assertThrows(
                IllegalArgumentException.class,
                () -> new AllowedClientValidator(" "));

        assertThrows(
                IllegalArgumentException.class,
                () -> new AllowedClientValidator("client-a,"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new AllowedClientValidator("client-a, ,client-b"));
    }

    private static Jwt token(Object clientId) {
        Instant now = Instant.now();

        Jwt.Builder builder = Jwt.withTokenValue("synthetic")
                .header("alg", "RS256")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60));

        if (clientId != null) {
            builder.claim("client_id", clientId);
        }

        return builder.build();
    }
}
