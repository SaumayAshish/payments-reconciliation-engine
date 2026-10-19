package com.saumay.payments.ingestion;

import com.saumay.payments.ingestion.normalise.LineErrorCode;
import java.util.Objects;

/** An invalid line, kept and queryable rather than silently dropped (FRD-02). */
public record RejectedLine(int lineNumber, LineErrorCode code, String message, String rawExcerpt) {

    public static final int MAX_EXCERPT_LENGTH = 200;

    public RejectedLine {
        if (lineNumber < 1) {
            throw new IllegalArgumentException("Line number must be at least 1");
        }
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        rawExcerpt = truncate(rawExcerpt);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_EXCERPT_LENGTH) {
            return text;
        }
        int end = MAX_EXCERPT_LENGTH;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}