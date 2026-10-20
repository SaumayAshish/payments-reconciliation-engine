package com.saumay.payments.ingestion.service;

import com.saumay.payments.ingestion.IngestionOutcome;
import java.util.Objects;

/** What an upload produced: the stored outcome, plus how many unsupported camt.053 elements were ignored. */
public record IngestionResult(IngestionOutcome outcome, int ignoredElements) {

    public IngestionResult {
        Objects.requireNonNull(outcome, "outcome");
        if (ignoredElements < 0) {
            throw new IllegalArgumentException("Ignored element count must not be negative");
        }
    }
}