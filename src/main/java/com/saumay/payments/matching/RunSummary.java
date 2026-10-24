package com.saumay.payments.matching;

/** The counts a run record stores (FRD-10 AC-10.1). All derived from the output, never from a clock. */
public record RunSummary(int statementLines, int ledgerEntries, int matches, int autoConfirmedMatches,
                         int reviewMatches, int ambiguousGroups, int exceptions, int carriedForward, int truncationNotes) {
}