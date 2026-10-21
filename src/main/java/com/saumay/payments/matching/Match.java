package com.saumay.payments.matching;

import java.util.List;
import java.util.Objects;

/**
 * A proposed or confirmed match. Tiers 1 to 3 have one line on each side; tier 4 has several on one side. Every
 * match carries its tier, score, band and a human-readable reason (AC-04.7).
 */
public record Match(MatchTier tier, MatchScore score, MatchBand band, String reason,
                    List<MatchableLine> statementSide, List<MatchableLine> ledgerSide) {

    public Match {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(band, "band");
        Objects.requireNonNull(reason, "reason");
        if (!tier.allows(score)) {
            throw new IllegalArgumentException("Score " + score + " is outside the range of tier " + tier);
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("A match needs a reason");
        }
        statementSide = List.copyOf(statementSide);
        ledgerSide = List.copyOf(ledgerSide);
        if (statementSide.isEmpty() || ledgerSide.isEmpty()) {
            throw new IllegalArgumentException("A match needs at least one line on each side");
        }
    }

    static Match ofPair(ScoredPair pair, MatchingConfig config) {
        return new Match(pair.tier(), pair.score(), config.classify(pair.score()), pair.reason(),
                List.of(pair.statement()), List.of(pair.ledger()));
    }
}