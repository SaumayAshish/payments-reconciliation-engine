package com.saumay.payments.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Local, test-only identity provider fixture.
 *
 * It publishes an ephemeral public RSA key and signs synthetic access tokens.
 * It is not an identity provider for application or production use.
 */
final class TestOidcIssuer implements AutoCloseable {

    static final String ISSUER = "https://api-test-issuer.example.test";
    static final String AUDIENCE = "payments-api-test";
    private static final String KEY_ID = "api-test-key";

    private final KeyPair keyPair;
    private final HttpServer server;

    TestOidcIssuer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();

        RSAKey publicKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .keyID(KEY_ID)
                .build();

        byte[] jwks = new JWKSet(publicKey).toString()
                .getBytes(StandardCharsets.UTF_8);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(jwks);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String jwkSetUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    String token(String subject, String role) throws Exception {
        Instant now = Instant.now();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(subject)
                .claim("roles", List.of(role))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(7200)))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(KEY_ID)
                        .build(),
                claims);

        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return jwt.serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}