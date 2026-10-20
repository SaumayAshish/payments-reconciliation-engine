package com.saumay.payments.ingestion;

import com.saumay.payments.domain.model.CanonicalLine;
import java.util.Objects;

/**
 * A valid line together with its 1-based position in the file. {@code entryId} is the ledger source-system
 * identifier; it is null for statement lines and for ledger lines that did not supply one.
 */
public record AcceptedLine(int lineNumber, CanonicalLine line, String entryId) {

    public AcceptedLine {
        if (lineNumber < 1) {
            throw new IllegalArgumentException("Line number must be at least 1");
        }
        Objects.requireNonNull(line, "line");
    }

    public AcceptedLine(int lineNumber, CanonicalLine line) {
        this(lineNumber, line, null);
    }
}
