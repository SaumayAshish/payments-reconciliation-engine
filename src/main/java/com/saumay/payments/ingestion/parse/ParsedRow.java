package com.saumay.payments.ingestion.parse;

import com.saumay.payments.ingestion.normalise.RawLine;
import java.util.Objects;

/**
 * One structurally valid row: text fields not yet validated, plus the source-system entry id (ledger only) and the
 * raw record text, kept so a later validation failure can store an excerpt.
 */
public record ParsedRow(int lineNumber, RawLine raw, String entryId, String rawText) {

    public ParsedRow {
        if (lineNumber < 1) {
            throw new IllegalArgumentException("Line number must be at least 1");
        }
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(rawText, "rawText");
    }
}