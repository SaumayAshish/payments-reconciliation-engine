package com.saumay.payments.ingestion;

import java.util.Optional;

/** Port: read a stored ingestion back. */
public interface IngestionReader {

    Optional<IngestionSummary> find(long id);
}