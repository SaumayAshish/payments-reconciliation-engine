package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class ExceptionActorResolverTest {

    private static final String ISSUER = "https://identity.example.test";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedJwtCannotBeOverriddenByActorHeader() {
        Jwt jwt = Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .claim("iss", ISSUER)
                .subject("real-user")
                .claim("roles", List.of("ANALYST"))
                .build();

        setAuthentication(jwt, true);

        String actor = ExceptionActorResolver.resolve("another-user");

        assertEquals(
                OidcActorId.fromVerifiedClaims(ISSUER, "real-user"),
                actor);
        assertNotEquals("another-user", actor);
    }

    @Test
    void authenticatedJwtDoesNotRequireAnActorHeader() {
        Jwt jwt = Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .claim("iss", ISSUER)
                .subject("real-user")
                .claim("roles", List.of("TEAM_LEAD"))
                .build();

        setAuthentication(jwt, true);

        assertEquals(
                OidcActorId.fromVerifiedClaims(ISSUER, "real-user"),
                ExceptionActorResolver.resolve(null));
    }

    @Test
    void unauthenticatedSecurityContextCannotFallBackToHeader() {
        setAuthentication("root", false);

        ApiException error = assertThrows(
                ApiException.class,
                () -> ExceptionActorResolver.resolve("root"));

        assertEquals(HttpStatus.UNAUTHORIZED, error.status());
    }

    @Test
    void authenticatedNonJwtPrincipalCannotFallBackToHeader() {
        setAuthentication("root", true);

        ApiException error = assertThrows(
                ApiException.class,
                () -> ExceptionActorResolver.resolve("root"));

        assertEquals(HttpStatus.UNAUTHORIZED, error.status());
    }

    @Test
    void legacyHeaderBehaviorRemainsWhenThereIsNoSecurityContext() {
        SecurityContextHolder.clearContext();

        assertEquals("alice", ExceptionActorResolver.resolve(" alice "));

        ApiException error = assertThrows(
                ApiException.class,
                () -> ExceptionActorResolver.resolve(null));

        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        assertEquals("ACTOR_REQUIRED", error.code());
    }

    private static void setAuthentication(Object principal, boolean authenticated) {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken(principal, null);

        authentication.setAuthenticated(authenticated);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}