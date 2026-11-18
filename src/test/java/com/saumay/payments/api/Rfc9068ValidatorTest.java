package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class Rfc9068ValidatorTest {

    private static final String ISSUER = "https://identity.example.test";
    private static final String AUDIENCE = "payments-api";

    private final TrustedJwtValidator validator =
            new TrustedJwtValidator(ISSUER, AUDIENCE);

    @Test
    void acceptsBothStandardAccessTokenTypeForms() {
        assertFalse(validator.validate(token("at+jwt", "")).hasErrors());
        assertFalse(validator.validate(token("application/at+jwt", "")).hasErrors());
    }

    @Test
    void rejectsMissingOrNonAccessTokenType() {
        assertTrue(validator.validate(token(null, "")).hasErrors());
        assertTrue(validator.validate(token("JWT", "")).hasErrors());
    }

    @Test
    void rejectsMissingRequiredRfcClaims() {
        assertTrue(validator.validate(token("at+jwt", "iat")).hasErrors());
        assertTrue(validator.validate(token("at+jwt", "jti")).hasErrors());
        assertTrue(validator.validate(token("at+jwt", "client_id")).hasErrors());
    }

    @Test
    void rejectsBlankTokenAndClientIdentifiers() {
        assertTrue(validator.validate(token("at+jwt", "blank-jti")).hasErrors());
        assertTrue(validator.validate(token("at+jwt", "blank-client")).hasErrors());
    }

    private static Jwt token(String type, String variation) {
        Instant now = Instant.now();

        Jwt.Builder builder = Jwt.withTokenValue("synthetic")
                .header("alg", "RS256")
                .claim("iss", ISSUER)
                .claim("aud", List.of(AUDIENCE))
                .subject("user-123")
                .expiresAt(now.plusSeconds(600));

        if (type != null) {
            builder.header("typ", type);
        }

        if (!"iat".equals(variation)) {
            builder.issuedAt(now);
        }

        if (!"jti".equals(variation)) {
            builder.claim("jti", "blank-jti".equals(variation) ? " " : "token-123");
        }

        if (!"client_id".equals(variation)) {
            builder.claim("client_id",
                    "blank-client".equals(variation) ? " " : "integration-client");
        }

        return builder.build();
    }
}