package com.saumay.payments.application;

import com.saumay.payments.matching.MatchingConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * What a caller asks for: reconcile these statement ingestions, as at this business date, with this configuration.
 * The ledger side is not chosen by the caller; the run takes every ledger entry committed when it starts.
 *
 * <p>The ingestion ids are a set: duplicates are dropped and the list is kept sorted, so the same request always
 * means the same run.
 */
public record RunRequest(LocalDate businessDate, List<Long> statementIngestionIds, MatchingConfig config) {

    public RunRequest {
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(statementIngestionIds, "statementIngestionIds");
        statementIngestionIds = statementIngestionIds.stream().distinct().sorted().toList();
        if (statementIngestionIds.isEmpty()) {
            throw new IllegalArgumentException("A run needs at least one statement ingestion");
        }
        if (statementIngestionIds.get(0) < 1) {
            throw new IllegalArgumentException("Ingestion ids are positive");
        }
    }
}