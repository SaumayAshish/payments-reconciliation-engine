package com.saumay.payments.application;

import com.saumay.payments.matching.MatchableLine;
import com.saumay.payments.matching.MatchingConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** The exact lines a run reconciles, loaded from its stored definition. Both lists are in load-sequence order. */
public record RunInputs(long runId, LocalDate businessDate, MatchingConfig config, List<MatchableLine> statements,
                        List<MatchableLine> ledger) {

    public RunInputs {
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(config, "config");
        statements = List.copyOf(statements);
        ledger = List.copyOf(ledger);
    }
}