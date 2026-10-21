package com.saumay.payments.matching;

import java.util.List;

/**
 * The result of one tier: matches made, groups held back as ambiguous, and the lines still free for the next
 * tier. The remaining lists are in canonical order so the outcome does not depend on input order.
 */
public record TierOutcome(List<Match> matches, List<AmbiguousGroup> ambiguous,
                          List<MatchableLine> remainingStatements, List<MatchableLine> remainingLedger) {

    public TierOutcome {
        matches = List.copyOf(matches);
        ambiguous = List.copyOf(ambiguous);
        remainingStatements = List.copyOf(remainingStatements);
        remainingLedger = List.copyOf(remainingLedger);
    }
}