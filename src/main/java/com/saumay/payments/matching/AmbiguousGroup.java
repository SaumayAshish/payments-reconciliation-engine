package com.saumay.payments.matching;

import java.util.List;
import java.util.Objects;

/**
 * Lines that cannot be paired safely because several pairings tie for the best score (FRD-05 AC-05.6). Nothing
 * here is auto-confirmed; the whole group goes to review with reason {@link #REASON}.
 */
public record AmbiguousGroup(MatchTier tier, MatchScore score, List<MatchableLine> statementSide,
                             List<MatchableLine> ledgerSide) {

    public static final String REASON = "AMBIGUOUS_TIE";

    public AmbiguousGroup {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(score, "score");
        statementSide = List.copyOf(statementSide);
        ledgerSide = List.copyOf(ledgerSide);
        if (statementSide.isEmpty() || ledgerSide.isEmpty()) {
            throw new IllegalArgumentException("An ambiguous group needs at least one line on each side");
        }
    }
}