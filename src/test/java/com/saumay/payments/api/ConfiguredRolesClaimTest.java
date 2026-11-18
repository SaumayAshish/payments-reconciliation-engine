package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

class ConfiguredRolesClaimTest {

    private static final String ISSUER = "https://identity.example.test/";
    private static final String CUSTOM_CLAIM = "https://payments.example.com/roles";

    @Test
    void mapsTheConfiguredNamespacedClaim() {
        var mapper = new VerifiedJwtActorMapper(CUSTOM_CLAIM);

        Jwt token = baseToken()
                .claim(CUSTOM_CLAIM, List.of("ANALYST"))
                .build();

        assertEquals(Role.ANALYST, mapper.map(token).role());
    }

    @Test
    void doesNotFallBackToTheDefaultClaim() {
        var mapper = new VerifiedJwtActorMapper(CUSTOM_CLAIM);

        Jwt token = baseToken()
                .claim("roles", List.of("ADMIN"))
                .build();

        ApiException error = assertThrows(
                ApiException.class,
                () -> mapper.map(token));

        assertEquals(HttpStatus.FORBIDDEN, error.status());
        assertEquals("ACCESS_DENIED", error.code());
    }

    @Test
    void rejectsMultipleRolesInTheConfiguredClaim() {
        var mapper = new VerifiedJwtActorMapper(CUSTOM_CLAIM);

        Jwt token = baseToken()
                .claim(CUSTOM_CLAIM, List.of("ANALYST", "CONTROLLER"))
                .build();

        ApiException error = assertThrows(
                ApiException.class,
                () -> mapper.map(token));

        assertEquals(HttpStatus.FORBIDDEN, error.status());
    }

    @Test
    void rejectsInvalidClaimConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VerifiedJwtActorMapper(""));

        assertThrows(
                IllegalArgumentException.class,
                () -> new VerifiedJwtActorMapper(" "));

        assertThrows(
                IllegalArgumentException.class,
                () -> new VerifiedJwtActorMapper(" roles "));
    }

    @Test
    void oidcConfigurationCreatesMapperWithConfiguredClaim() {
        var mapper = new OidcSecurityConfig()
                .verifiedJwtActorMapper(CUSTOM_CLAIM);

        Jwt token = baseToken()
                .claim(CUSTOM_CLAIM, List.of("AUDITOR"))
                .build();

        assertEquals(Role.AUDITOR, mapper.map(token).role());
    }

    private static Jwt.Builder baseToken() {
        return Jwt.withTokenValue("synthetic")
                .header("alg", "RS256")
                .claim("iss", ISSUER)
                .subject("test-user");
    }
}