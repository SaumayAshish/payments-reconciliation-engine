package com.saumay.payments.matching.corpus;

/**
 * Scenario proportions in parts per thousand of statement lines (spec section 4). Changing a profile is
 * configuration, not code. {@code noStatementPermille} adds ledger-only entries on top of the statement lines.
 */
public record CorpusProfile(String name, int exact, int tolerance1d, int tolerance2d3d, int fuzzy, int oneToMany,
                            int noLedger, int hardNegative, int noStatementPermille) {

    public CorpusProfile {
        if (exact + tolerance1d + tolerance2d3d + fuzzy + oneToMany + noLedger + hardNegative != 1000) {
            throw new IllegalArgumentException("The seven statement scenarios must sum to 1000 per mille");
        }
    }

    /** The placeholder profile of the spec: 82, 10, 2, 2, 1, 1.5 and 1.5 percent, plus 1 percent ledger-only. */
    public static CorpusProfile standard() {
        return new CorpusProfile("standard", 820, 100, 20, 20, 10, 15, 15, 10);
    }
}