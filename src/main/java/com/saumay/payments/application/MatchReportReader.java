package com.saumay.payments.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Reads match statistics for one completed reconciliation run. */
public interface MatchReportReader {

    Optional<Report> completedRun(long runId);

    record Band(String tier, String band, long count) {
        public Band {
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(band, "band");
            if (count < 1) {
                throw new IllegalArgumentException("count must be positive");
            }
        }
    }

    record Report(long runId, LocalDate businessDate, int statementLineCount, int ledgerEntryCount,
            int matchCount, int autoMatchCount, int reviewMatchCount, int exceptionCount,
            List<Band> bands) {
        public Report {
            Objects.requireNonNull(businessDate, "businessDate");
            bands = List.copyOf(bands);
        }
    }
}