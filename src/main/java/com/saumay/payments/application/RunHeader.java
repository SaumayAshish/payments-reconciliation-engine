package com.saumay.payments.application;

import com.saumay.payments.matching.MatchingConfig;
import com.saumay.payments.matching.RunSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A stored run without its result rows: its definition (business date, configuration, statement ingestions, ledger
 * cut-off) and how far it got. {@code startedAt}, {@code completedAt}, {@code durationMillis}, {@code outputHash},
 * {@code summary} and {@code failureReason} are null until the run reaches the state that sets them.
 */
public record RunHeader(long id, LocalDate businessDate, RunStatus status, MatchingConfig config,
                        long ledgerCutoffSeq, List<Long> statementIngestionIds, Instant createdAt, Instant startedAt,
                        Instant completedAt, Long durationMillis, String outputHash, RunSummary summary, String failureReason) {

    public RunHeader {
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(createdAt, "createdAt");
        statementIngestionIds = List.copyOf(statementIngestionIds);
    }
}