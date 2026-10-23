package com.saumay.payments.matching.corpus;

import com.saumay.payments.domain.model.LineKey;
import java.util.List;
import java.util.Objects;

/**
 * Ground truth for one group of lines, created before any noise is applied so it is correct by construction.
 * For a match expectation the two key lists are exactly the lines that must appear together in one match; for
 * {@link Expected#AMBIGUOUS} they are exactly the lines of the one group; for {@link Expected#UNMATCHED} each
 * listed line must be left alone.
 */
public record TruthCase(int id, Scenario scenario, Expected expected, List<LineKey> statements,
                        List<LineKey> ledger) {

    public TruthCase {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(expected, "expected");
        statements = List.copyOf(statements);
        ledger = List.copyOf(ledger);
        if (statements.isEmpty() && ledger.isEmpty()) {
            throw new IllegalArgumentException("A truth case needs at least one line");
        }
        if (expected.isMatch() && (statements.isEmpty() || ledger.isEmpty())) {
            throw new IllegalArgumentException("A match expectation needs a line on each side");
        }
    }
}