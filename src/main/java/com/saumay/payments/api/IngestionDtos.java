package com.saumay.payments.api;

import com.saumay.payments.ingestion.IngestionSummary;
import java.util.List;

/** Response contracts for the ingestion endpoints. Domain types never cross the API boundary. */
public final class IngestionDtos {

    private IngestionDtos() {
    }

    /** Result of an upload. */
    public record Created(long ingestionId, int acceptedCount, int rejectedCount, boolean duplicate,
                          int ignoredElements) {
    }

    public record RejectedLineView(int lineNumber, String code, String message, String rawExcerpt) {
    }

    /** A stored ingestion. {@code rejectedLines} may be capped; {@code rejectedCount} is always the true total. */
    public record Detail(long id, String kind, String format, String fileHash, long byteSize, int acceptedCount,
                         int rejectedCount, String ingestedAt, List<RejectedLineView> rejectedLines) {

        static Detail from(IngestionSummary s) {
            return new Detail(s.id(), s.kind().name(), s.format().name(), s.fileHash().hex(), s.byteSize(),
                    s.acceptedCount(), s.rejectedCount(), s.ingestedAt().toString(),
                    s.rejectedLines().stream()
                            .map(r -> new RejectedLineView(r.lineNumber(), r.code().name(), r.message(),
                                    r.rawExcerpt()))
                            .toList());
        }
    }
}