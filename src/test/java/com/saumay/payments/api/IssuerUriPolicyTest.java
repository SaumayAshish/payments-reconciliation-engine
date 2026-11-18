package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IssuerUriPolicyTest {

    @Test
    void acceptsHttpsIssuerAndPreservesTheExactValue() {
        String issuer = "https://identity.example.test:8443/realm/example/";

        assertEquals(issuer, IssuerUriPolicy.validate(issuer));
    }

    @Test
    void rejectsMissingMalformedAndNonHttpsIssuers() {
        assertThrows(IllegalArgumentException.class,
                () -> IssuerUriPolicy.validate(null));
        assertThrows(IllegalArgumentException.class,
                () -> IssuerUriPolicy.validate(" "));
        assertThrows(IllegalArgumentException.class,
                () -> IssuerUriPolicy.validate("not a URL"));
        assertThrows(IllegalArgumentException.class,
                () -> IssuerUriPolicy.validate("/issuer"));
        assertThrows(IllegalArgumentException.class,
                () -> IssuerUriPolicy.validate("http://identity.example.test"));
        assertThrows(IllegalArgumentException.class,
                () -> IssuerUriPolicy.validate("file:///tmp/issuer"));
    }

    @Test
    void rejectsQueryFragmentUserInformationAndInvalidPorts() {
        assertThrows(IllegalArgumentException.class,
                () -> IssuerUriPolicy.validate("https://identity.example.test/issuer?tenant=1"));
        assertThrows(IllegalArgumentException.class,
                () -> IssuerUriPolicy.validate("https://identity.example.test/issuer#fragment"));
        assertThrows(IllegalArgumentException.class,
                () -> IssuerUriPolicy.validate("https://user@identity.example.test/issuer"));
        assertThrows(IllegalArgumentException.class,
                () -> IssuerUriPolicy.validate("https://identity.example.test:0/issuer"));
        assertThrows(IllegalArgumentException.class,
                () -> IssuerUriPolicy.validate("https://identity.example.test:65536/issuer"));
    }

    @Test
    void rejectsAnIssuerLongerThanThePersistenceLimit() {
        String issuer = "https://identity.example.test/" + "a".repeat(2048);

        assertThrows(IllegalArgumentException.class,
                () -> IssuerUriPolicy.validate(issuer));
    }
}