package com.saumay.payments.ingestion;

/**
 * Port: persists one parsed file atomically and idempotently. Storing the same (kind, content hash) again changes
 * nothing and returns the original ingestion with duplicate = true.
 */
public interface IngestionStore {

    IngestionOutcome store(IngestionRequest request);
}