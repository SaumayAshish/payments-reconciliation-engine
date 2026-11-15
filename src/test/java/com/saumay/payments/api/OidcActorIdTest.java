package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OidcActorIdTest {

    @Test
    void sameVerifiedIdentityAlwaysProducesSameOpaqueId() {
        String first = OidcActorId.fromVerifiedClaims(
                "https://identity.example.test", "user-123");

        String second = OidcActorId.fromVerifiedClaims(
                "https://identity.example.test", "user-123");

        assertEquals(first, second);
        assertTrue(first.matches("oidc-[0-9a-f]{64}"));
    }

    @Test
    void issuerAndSubjectAreCaseSensitive() {
        String base = OidcActorId.fromVerifiedClaims(
                "https://identity.example.test", "Alice");

        assertNotEquals(base, OidcActorId.fromVerifiedClaims(
                "https://identity.example.test", "alice"));

        assertNotEquals(base, OidcActorId.fromVerifiedClaims(
                "https://IDENTITY.example.test", "Alice"));
    }

    @Test
    void distinctIssuerSubjectBoundariesDoNotProduceSameId() {
        String first = OidcActorId.fromVerifiedClaims("issuer-a", "bc");
        String second = OidcActorId.fromVerifiedClaims("issuer-ab", "c");

        assertNotEquals(first, second);
    }

    @Test
    void nullOrBlankClaimsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> OidcActorId.fromVerifiedClaims(null, "user"));

        assertThrows(NullPointerException.class,
                () -> OidcActorId.fromVerifiedClaims("issuer", null));

        assertThrows(IllegalArgumentException.class,
                () -> OidcActorId.fromVerifiedClaims(" ", "user"));

        assertThrows(IllegalArgumentException.class,
                () -> OidcActorId.fromVerifiedClaims("issuer", " "));
    }

    @Test
    void claimsAreNotTrimmed() {
        assertNotEquals(
                OidcActorId.fromVerifiedClaims("issuer", "user"),
                OidcActorId.fromVerifiedClaims("issuer", " user"));
    }
}