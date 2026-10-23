package com.saumay.payments.matching;

import java.util.List;

/**
 * Everything the four tiers decided for one pair of inputs. Every input line appears exactly once: in a match,
 * in an ambiguous group, or in one of the unmatched lists. Matches are ordered by tier and then by the tier's
 * own deterministic order, and every list is built without depending on input order (ADR-0003).
 */
public record PipelineResult(List<Match> matches, List<AmbiguousGroup> ambiguous,
                             List<MatchableLine> unmatchedStatements, List<MatchableLine> unmatchedLedger, List<String> notes) {

    public PipelineResult {
        matches = List.copyOf(matches);
        ambiguous = List.copyOf(ambiguous);
        unmatchedStatements = List.copyOf(unmatchedStatements);
        unmatchedLedger = List.copyOf(unmatchedLedger);
        notes = List.copyOf(notes);
    }
}