package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityContextActorContextTest {

    private static final String ISSUER = "https://identity.example.test";

    private final SecurityContextActorContext actors =
            new SecurityContextActorContext(new VerifiedJwtActorMapper());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedJwtSuppliesActorAndHeaderCannotSpoofIt() {
        Jwt jwt = token("real-user", List.of("CONTROLLER"));
        setAuthentication(jwt, true);

        Actor actor = actors.resolve("different-user");

        assertEquals(
                OidcActorId.fromVerifiedClaims(ISSUER, "real-user"),
                actor.id());
        assertEquals(Role.CONTROLLER, actor.role());
    }

    @Test
    void missingAuthenticationIsRejected() {
        SecurityContextHolder.clearContext();

        ApiException error = assertThrows(
                ApiException.class, () -> actors.resolve("root"));

        assertEquals(HttpStatus.UNAUTHORIZED, error.status());
        assertEquals("AUTHENTICATION_REQUIRED", error.code());
    }

    @Test
    void unauthenticatedJwtIsRejected() {
        setAuthentication(token("user", List.of("ADMIN")), false);

        ApiException error = assertThrows(
                ApiException.class, () -> actors.resolve("root"));

        assertEquals(HttpStatus.UNAUTHORIZED, error.status());
    }

    @Test
    void nonJwtPrincipalIsRejectedEvenWhenMarkedAuthenticated() {
        setAuthentication("root", true);

        ApiException error = assertThrows(
                ApiException.class, () -> actors.resolve("root"));

        assertEquals(HttpStatus.UNAUTHORIZED, error.status());
    }

    @Test
    void authenticatedJwtWithUnknownRoleIsDenied() {
        setAuthentication(token("user", List.of("UNKNOWN")), true);

        ApiException error = assertThrows(
                ApiException.class, () -> actors.resolve(null));

        assertEquals(HttpStatus.FORBIDDEN, error.status());
        assertEquals("ACCESS_DENIED", error.code());
    }

    private static void setAuthentication(Object principal, boolean authenticated) {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken(principal, null);
        authentication.setAuthenticated(authenticated);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static Jwt token(String subject, List<String> roles) {
        return Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .claim("iss", ISSUER)
                .subject(subject)
                .claim("roles", roles)
                .build();
    }
}