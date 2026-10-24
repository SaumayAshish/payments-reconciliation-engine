package com.saumay.payments.application;

import com.saumay.payments.domain.model.LineSource;
import java.time.LocalDate;
import java.util.List;

/**
 * Read side of a stored run (FRD-10). Kept apart from {@link RunStore}, which owns the run's lifecycle and writes:
 * reading results never changes anything, and later phases (exception cases) read the same data.
 *
 * <p>Results are paged by a cursor on the row ordinal, not by offset, so a page costs the same however far in it is
 * and never skips or repeats a row. Pass 0 for the first page, then the last ordinal of the previous one.
 */
public interface RunResultReader {

    /** Up to {@code limit} matches with an ordinal greater than {@code afterOrdinal}, in ordinal order. */
    List<MatchView> matches(long runId, int afterOrdinal, int limit);

    /** Up to {@code limit} exceptions with an ordinal greater than {@code afterOrdinal}, in ordinal order. */
    List<ExceptionView> exceptions(long runId, int afterOrdinal, int limit);

    /** Ledger sequences carried forward by the run, ascending, strictly after {@code afterLedgerSeq}. */
    List<Long> carriedForward(long runId, long afterLedgerSeq, int limit);

    /** The engine notes of the run, in order. */
    List<String> notes(long runId);

    /** A statement line or ledger entry, as much of it as a reader needs to recognise it. */
    record LineView(LineSource source, long seq, String reference, long amountMinor, String currency,
                    LocalDate valueDate) {
    }

    record MatchView(int ordinal, String tier, int scoreMilli, String band, String reason, List<LineView> lines) {

        public MatchView {
            lines = List.copyOf(lines);
        }
    }

    record ExceptionView(int ordinal, String kind, String reason, String detail, List<LineView> subjects,
                         List<LineView> candidates) {

        public ExceptionView {
            subjects = List.copyOf(subjects);
            candidates = List.copyOf(candidates);
        }
    }
}