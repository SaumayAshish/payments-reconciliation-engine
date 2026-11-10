package com.saumay.payments.application;

import com.saumay.payments.domain.cases.CaseKind;
import java.util.Map;
import java.util.Optional;

/** Reads aggregate values used by operational gauges. */
public interface OperationalMetricsReader {

    Optional<RunSnapshot> latestCompletedRun();

    Map<CaseKind, Long> unresolvedByKind();

    record RunSnapshot(long statementLines, long autoMatchedStatementLines,
            long exact, long tolerance, long fuzzy, long oneToMany) {
        public RunSnapshot {
            if (statementLines < 0 || autoMatchedStatementLines < 0
                    || exact < 0 || tolerance < 0 || fuzzy < 0 || oneToMany < 0) {
                throw new IllegalArgumentException("metric counts cannot be negative");
            }
        }
    }
}