package com.saumay.payments.ingestion.parse;

import com.saumay.payments.ingestion.RejectedLine;
import java.util.List;

/** Parser output: rows that are structurally sound, and rows rejected for structure (nothing is dropped). */
public record ParsedFile(List<ParsedRow> rows, List<RejectedLine> rejected) {

    public ParsedFile {
        rows = List.copyOf(rows);
        rejected = List.copyOf(rejected);
    }
}