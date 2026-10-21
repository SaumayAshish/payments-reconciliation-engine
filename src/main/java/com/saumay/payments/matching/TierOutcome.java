package com.saumay.payments.matching;

import java.util.List;

/**
 * The result of one tier: matches made, groups held back as ambiguous, the lines still free for the next tier,
 * and any notes the tier wants recorded in the run output (for example a truncated candidate search). The
 * remaining lists are in canonical order and the notes are in a fixed order, so the outcome does not depend on
 * input order.
 */
public record TierOutcome(List<Match> matches, List<AmbiguousGroup> ambiguous,
                          List<MatchableLine> remainingStatements, List<MatchableLine> remainingLedger, List<String> notes) {

    public TierOutcome {
        matches = List.copyOf(matches);
        ambiguous = List.copyOf(ambiguous);
        remainingStatements = List.copyOf(remainingStatements);
        remainingLedger = List.copyOf(remainingLedger);
        notes = List.copyOf(notes);
    }
}
