package com.saumay.payments.api;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Creates an opaque, stable actor identifier from an already validated OIDC
 * issuer and subject. This class does not validate tokens.
 */
public final class OidcActorId {

    private static final String PREFIX = "oidc-";

    private OidcActorId() {
    }

    /**
     * Issuer and subject are case-sensitive. Neither value is trimmed or
     * normalized because doing so could merge distinct identities.
     *
     * @param issuer issuer from a validated token
     * @param subject subject from a validated token
     * @return opaque actor identifier
     */
    public static String fromVerifiedClaims(String issuer, String subject) {
        requireNonBlank(issuer, "issuer");
        requireNonBlank(subject, "subject");

        byte[] issuerBytes = issuer.getBytes(StandardCharsets.UTF_8);
        byte[] subjectBytes = subject.getBytes(StandardCharsets.UTF_8);

        MessageDigest digest = sha256();
        updateLength(digest, issuerBytes.length);
        digest.update(issuerBytes);
        updateLength(digest, subjectBytes.length);
        digest.update(subjectBytes);

        return PREFIX + HexFormat.of().formatHex(digest.digest());
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void updateLength(MessageDigest digest, int length) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}