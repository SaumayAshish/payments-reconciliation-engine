package com.saumay.payments.matching;

import com.saumay.payments.domain.model.CanonicalLine;
import com.saumay.payments.domain.model.LineKey;
import java.util.Comparator;
import java.util.Objects;

/**
 * A canonical line plus the load sequence it was stored with. The sequence is only a tie-breaker (ADR-0003); it
 * is assigned at ingestion, never by the matching engine.
 */
public record MatchableLine(long seq, CanonicalLine line) {

    /** The single deterministic order for lines of either side: value date, then load sequence, then line key. */
    public static final Comparator<MatchableLine> CANONICAL_ORDER = Comparator
            .comparing((MatchableLine m) -> m.line().valueDate())
            .thenComparingLong(MatchableLine::seq)
            .thenComparing(m -> m.line().lineKey());

    public MatchableLine {
        if (seq < 0) {
            throw new IllegalArgumentException("Sequence must not be negative");
        }
        Objects.requireNonNull(line, "line");
    }

    public LineKey key() {
        return line.lineKey();
    }
}