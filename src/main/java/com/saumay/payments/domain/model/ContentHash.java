package com.saumay.payments.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * SHA-256 of raw bytes as 64 lower-case hex characters. The identity of an ingested file (ADR-0002).
 */
public record ContentHash(String hex) {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    public ContentHash {
        if (hex == null || !HEX64.matcher(hex).matches()) {
            throw new IllegalArgumentException("Content hash must be 64 lower-case hex characters");
        }
    }

    public static ContentHash of(byte[] content) {
        return new ContentHash(sha256Hex(content));
    }

    public static ContentHash ofUtf8(String text) {
        return of(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    @Override
    public String toString() {
        return hex;
    }
}