package com.saumay.payments.ingestion;

/** What the caller gets back. {@code duplicate} is true when the same bytes were already ingested (FRD AC-01.2). */
public record IngestionOutcome(long ingestionId, int acceptedCount, int rejectedCount, boolean duplicate) {
}