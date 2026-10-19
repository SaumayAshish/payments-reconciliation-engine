package com.saumay.payments.ingestion;

import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineKey;
import com.saumay.payments.domain.model.LineSource;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the store needs to persist one parsed file. Lines are sorted by line number so storage order does not
 * depend on how the parser happened to produce them (ADR-0003). The constructor enforces that every line belongs to
 * this file: right side, right key, and no line number used twice.
 */
public record IngestionRequest(
        LineSource kind,
        ContentHash fileHash,
        FileFormat format,
        long byteSize,
        List<AcceptedLine> accepted,
        List<RejectedLine> rejected) {

    public IngestionRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fileHash, "fileHash");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(rejected, "rejected");
        if (byteSize < 0) {
            throw new IllegalArgumentException("Byte size must not be negative");
        }
        accepted = accepted.stream().sorted(Comparator.comparingInt(AcceptedLine::lineNumber)).toList();
        rejected = rejected.stream().sorted(Comparator.comparingInt(RejectedLine::lineNumber)).toList();

        Set<Integer> seen = new HashSet<>();
        for (AcceptedLine a : accepted) {
            if (a.line().source() != kind) {
                throw new IllegalArgumentException("Line " + a.lineNumber() + " belongs to the other side");
            }
            if (!a.line().lineKey().equals(LineKey.of(fileHash, a.lineNumber()))) {
                throw new IllegalArgumentException("Line " + a.lineNumber() + " has a key that does not match this file");
            }
            if (!seen.add(a.lineNumber())) {
                throw new IllegalArgumentException("Line number " + a.lineNumber() + " is used twice");
            }
        }
        for (RejectedLine r : rejected) {
            if (!seen.add(r.lineNumber())) {
                throw new IllegalArgumentException("Line number " + r.lineNumber() + " is used twice");
            }
        }
    }
}