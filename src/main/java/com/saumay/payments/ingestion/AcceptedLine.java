package com.saumay.payments.ingestion;

import com.saumay.payments.domain.model.CanonicalLine;
import java.util.Objects;

/** A valid line together with its 1-based position in the file. */
public record AcceptedLine(int lineNumber, CanonicalLine line) {

    public AcceptedLine {
        if (lineNumber < 1) {
            throw new IllegalArgumentException("Line number must be at least 1");
        }
        Objects.requireNonNull(line, "line");
    }
}
