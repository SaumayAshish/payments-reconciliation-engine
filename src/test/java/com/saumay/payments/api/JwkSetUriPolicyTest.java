package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class JwkSetUriPolicyTest {

    @Test
    void acceptsHttpsEndpoint() {
        String uri = "https://identity.example.test/.well-known/jwks.json";

        assertEquals(uri, JwkSetUriPolicy.validate(uri, false));
    }

    @Test
    void rejectsPlainHttpByDefaultIncludingLoopback() {
        assertThrows(IllegalArgumentException.class,
                () -> JwkSetUriPolicy.validate("http://127.0.0.1:8080/jwks", false));
        assertThrows(IllegalArgumentException.class,
                () -> JwkSetUriPolicy.validate("http://identity.example.test/jwks", false));
    }

    @Test
    void explicitOptInAllowsOnlyLiteralIpv4LoopbackHttp() {
        String loopback = "http://127.0.0.1:8080/jwks";

        assertEquals(loopback, JwkSetUriPolicy.validate(loopback, true));
        assertThrows(IllegalArgumentException.class,
                () -> JwkSetUriPolicy.validate("http://localhost:8080/jwks", true));
        assertThrows(IllegalArgumentException.class,
                () -> JwkSetUriPolicy.validate("http://127.0.0.2:8080/jwks", true));
        assertThrows(IllegalArgumentException.class,
                () -> JwkSetUriPolicy.validate("http://identity.example.test/jwks", true));
    }

    @Test
    void rejectsMalformedUrisCredentialsFragmentsAndOtherSchemes() {
        assertThrows(IllegalArgumentException.class,
                () -> JwkSetUriPolicy.validate(null, false));
        assertThrows(IllegalArgumentException.class,
                () -> JwkSetUriPolicy.validate(" ", false));
        assertThrows(IllegalArgumentException.class,
                () -> JwkSetUriPolicy.validate("/jwks", false));
        assertThrows(IllegalArgumentException.class,
                () -> JwkSetUriPolicy.validate("file:///tmp/jwks", false));
        assertThrows(IllegalArgumentException.class,
                () -> JwkSetUriPolicy.validate("https://user@example.test/jwks", false));
        assertThrows(IllegalArgumentException.class,
                () -> JwkSetUriPolicy.validate("https://example.test/jwks#fragment", false));
        assertThrows(IllegalArgumentException.class,
                () -> JwkSetUriPolicy.validate("https://example.test:0/jwks", false));
    }
}