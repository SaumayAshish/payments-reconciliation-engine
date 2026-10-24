package com.saumay.payments.matching;

import java.util.List;
import java.util.Objects;

/**
 * One exception raised by a run (FRD-06). {@code subjects} are all the lines the exception is about, statement
 * lines first, each side in canonical order. {@code candidates} are at most three alternative lines in rank order
 * (AC-05.5), present where the engine has real alternatives to show: the options of a tie, or the look-alike of
 * a mismatch. Time and identifiers are not part of it, so it can be hashed.
 */
public record RunException(ExceptionKind kind, List<MatchableLine> subjects, String reason, String detail,
                           List<MatchableLine> candidates) {

    public static final int MAX_CANDIDATES = 3;

    public RunException {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(detail, "detail");
        subjects = List.copyOf(subjects);
        candidates = List.copyOf(candidates);
        if (subjects.isEmpty()) {
            throw new IllegalArgumentException("An exception needs at least one subject line");
        }
        if (candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("An exception carries at most " + MAX_CANDIDATES + " candidates");
        }
    }

    /** The natural key of FRD-06 AC-06.3: kind plus the first subject line. */
    public String naturalKey() {
        return kind + ":" + subjects.get(0).key().value();
    }
}