package com.saumay.payments.matching;

import com.saumay.payments.domain.model.LineSource;
import java.util.Comparator;
import java.util.Objects;

/** One eligible (statement line, ledger entry) pairing with the tier that produced it, its score and a reason. */
public record ScoredPair(MatchableLine statement, MatchableLine ledger, MatchTier tier, MatchScore score,
                         String reason) {

    /** Best first: score descending, then the ledger entry's canonical order, then the statement line's. */
    public static final Comparator<ScoredPair> ORDER = Comparator
            .comparing(ScoredPair::score, Comparator.<MatchScore>reverseOrder())
            .thenComparing(ScoredPair::ledger, MatchableLine.CANONICAL_ORDER)
            .thenComparing(ScoredPair::statement, MatchableLine.CANONICAL_ORDER);

    public ScoredPair {
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(reason, "reason");
        if (statement.line().source() != LineSource.STATEMENT) {
            throw new IllegalArgumentException("The statement side must be a statement line");
        }
        if (ledger.line().source() != LineSource.LEDGER) {
            throw new IllegalArgumentException("The ledger side must be a ledger entry");
        }
        if (!tier.allows(score)) {
            throw new IllegalArgumentException("Score " + score + " is outside the range of tier " + tier);
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("A pair needs a reason");
        }
    }
}