package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

class VerifiedJwtActorMapperTest {

    private static final String ISSUER = "https://identity.example.test";
    private final VerifiedJwtActorMapper mapper = new VerifiedJwtActorMapper();

    @Test
    void mapsEverySupportedRoleAndUsesOpaqueIdentity() {
        for (Role role : Role.values()) {
            Actor actor = mapper.map(token("user-123", List.of(role.name())));

            assertEquals(role, actor.role());
            assertEquals(
                    OidcActorId.fromVerifiedClaims(ISSUER, "user-123"),
                    actor.id());
        }
    }

    @Test
    void differentSubjectsRemainDifferentActors() {
        Actor first = mapper.map(token("Alice", List.of("CONTROLLER")));
        Actor second = mapper.map(token("alice", List.of("CONTROLLER")));

        assertNotEquals(first.id(), second.id());
    }

    @Test
    void rejectsMissingEmptyAndMultipleRoles() {
        assertDenied(tokenWithoutRoles("user"));
        assertDenied(token("user", List.of()));
        assertDenied(token("user", List.of("ANALYST", "CONTROLLER")));
    }

    @Test
    void rejectsUnknownAndIncorrectlyTypedRoles() {
        assertDenied(token("user", List.of("UNKNOWN")));
        assertDenied(token("user", List.of("analyst")));
        assertDenied(token("user", "ADMIN"));
        assertDenied(token("user", List.of(42)));
    }

    @Test
    void rejectsMissingSubject() {
        Jwt token = Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .claim("iss", ISSUER)
                .claim("roles", List.of("ANALYST"))
                .build();

        ApiException error = assertThrows(ApiException.class, () -> mapper.map(token));
        assertEquals(HttpStatus.UNAUTHORIZED, error.status());
        assertEquals("AUTHENTICATION_REQUIRED", error.code());
    }

    @Test
    void rejectsMissingIssuer() {
        Jwt token = Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .subject("user")
                .claim("roles", List.of("ANALYST"))
                .build();

        ApiException error = assertThrows(ApiException.class, () -> mapper.map(token));
        assertEquals(HttpStatus.UNAUTHORIZED, error.status());
        assertEquals("AUTHENTICATION_REQUIRED", error.code());
    }

    private void assertDenied(Jwt token) {
        ApiException error = assertThrows(ApiException.class, () -> mapper.map(token));
        assertEquals(HttpStatus.FORBIDDEN, error.status());
        assertEquals("ACCESS_DENIED", error.code());
    }

    private static Jwt token(String subject, Object roles) {
        return Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .claim("iss", ISSUER)
                .subject(subject)
                .claim("roles", roles)
                .build();
    }

    private static Jwt tokenWithoutRoles(String subject) {
        return Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .claim("iss", ISSUER)
                .subject(subject)
                .build();
    }
}