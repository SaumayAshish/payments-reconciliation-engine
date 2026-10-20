package com.saumay.payments.ingestion;

import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineSource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A stored ingestion as the read side sees it. {@code rejectedLines} may be capped; counts are always exact. */
public record IngestionSummary(
        long id,
        LineSource kind,
        FileFormat format,
        ContentHash fileHash,
        long byteSize,
        int acceptedCount,
        int rejectedCount,
        Instant ingestedAt,
        List<RejectedLine> rejectedLines) {

    public IngestionSummary {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(fileHash, "fileHash");
        Objects.requireNonNull(ingestedAt, "ingestedAt");
        rejectedLines = List.copyOf(rejectedLines);
    }
}