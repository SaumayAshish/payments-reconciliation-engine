package com.saumay.payments.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Deterministic identity of a line: {@code hex(SHA-256(fileHash + ":" + lineNumber))}. Never random (FRD data
 * dictionary), so re-running ingestion or replaying a run yields identical keys.
 */
public record LineKey(String value) implements Comparable<LineKey> {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    public LineKey {
        if (value == null || !HEX64.matcher(value).matches()) {
            throw new IllegalArgumentException("Line key must be 64 lower-case hex characters");
        }
    }

    /** @param lineNumber 1-based line number within the file (the header row is not a line) */
    public static LineKey of(ContentHash fileHash, int lineNumber) {
        if (lineNumber < 1) {
            throw new IllegalArgumentException("Line number must be at least 1");
        }
        String material = fileHash.hex() + ":" + lineNumber;
        return new LineKey(ContentHash.sha256Hex(material.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public int compareTo(LineKey other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}