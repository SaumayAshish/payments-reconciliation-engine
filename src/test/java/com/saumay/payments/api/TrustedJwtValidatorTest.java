package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class TrustedJwtValidatorTest {

    private static final String ISSUER = "https://identity.example.test";
    private static final String AUDIENCE = "payments-api";

    private static KeyPair trustedKey;
    private static KeyPair otherKey;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        trustedKey = generator.generateKeyPair();
        otherKey = generator.generateKeyPair();
    }

    @Test
    void acceptsCorrectlySignedTokenWithRequiredClaims() throws Exception {
        String token = signedToken(
                trustedKey, ISSUER, AUDIENCE, "user-123",
                Instant.now().plusSeconds(600));

        assertEquals("user-123", decoder().decode(token).getSubject());
    }

    @Test
    void rejectsTokenSignedByAnotherKey() throws Exception {
        String token = signedToken(
                otherKey, ISSUER, AUDIENCE, "user-123",
                Instant.now().plusSeconds(600));

        assertThrows(JwtException.class, () -> decoder().decode(token));
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        String token = signedToken(
                trustedKey, "https://other.example.test", AUDIENCE, "user-123",
                Instant.now().plusSeconds(600));

        assertThrows(JwtException.class, () -> decoder().decode(token));
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        String token = signedToken(
                trustedKey, ISSUER, "another-api", "user-123",
                Instant.now().plusSeconds(600));

        assertThrows(JwtException.class, () -> decoder().decode(token));
    }

    @Test
    void rejectsExpiredAndMissingExpiry() throws Exception {
        String expired = signedToken(
                trustedKey, ISSUER, AUDIENCE, "user-123",
                Instant.now().minusSeconds(3600));
        String noExpiry = signedToken(
                trustedKey, ISSUER, AUDIENCE, "user-123", null);

        assertThrows(JwtException.class, () -> decoder().decode(expired));
        assertThrows(JwtException.class, () -> decoder().decode(noExpiry));
    }

    @Test
    void rejectsBlankSubjectAndMalformedToken() throws Exception {
        String noSubject = signedToken(
                trustedKey, ISSUER, AUDIENCE, " ",
                Instant.now().plusSeconds(600));

        assertThrows(JwtException.class, () -> decoder().decode(noSubject));
        assertThrows(JwtException.class, () -> decoder().decode("not-a-jwt"));
    }

    private static NimbusJwtDecoder decoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey((RSAPublicKey) trustedKey.getPublic())
                .validateType(false)
                .build();
        decoder.setJwtValidator(new TrustedJwtValidator(ISSUER, AUDIENCE));
        return decoder;
    }

    private static String signedToken(
            KeyPair key,
            String issuer,
            String audience,
            String subject,
            Instant expiresAt) throws Exception {

        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .jwtID(java.util.UUID.randomUUID().toString())
                .claim("client_id", "integration-client")
                .issuer(issuer)
                .audience(audience)
                .subject(subject)
                .issueTime(Date.from(Instant.now()));

        if (expiresAt != null) {
            claims.expirationTime(Date.from(expiresAt));
        }

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(new com.nimbusds.jose.JOSEObjectType("at+jwt"))
                        .build(),
                claims.build());

        jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
        return jwt.serialize();
    }
}