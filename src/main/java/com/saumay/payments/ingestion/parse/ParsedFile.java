package com.saumay.payments.ingestion.parse;

import com.saumay.payments.ingestion.RejectedLine;
import java.util.List;

/**
 * Parser output: rows that are structurally sound, rows rejected for structure (nothing is dropped), and how many
 * unsupported elements were ignored (camt.053 only; FRD 4.4).
 */
public record ParsedFile(List<ParsedRow> rows, List<RejectedLine> rejected, int ignoredElements) {

    public ParsedFile {
        rows = List.copyOf(rows);
        rejected = List.copyOf(rejected);
        if (ignoredElements < 0) {
            throw new IllegalArgumentException("Ignored element count must not be negative");
        }
    }

    public ParsedFile(List<ParsedRow> rows, List<RejectedLine> rejected) {
        this(rows, rejected, 0);
    }
}
