package com.saumay.payments.matching.corpus;

import com.saumay.payments.matching.MatchableLine;
import java.util.List;

/** A generated corpus: both sides, the ground truth, and exactly what produced them. */
public record Corpus(String generatorVersion, long seed, int requestedStatementLines, CorpusProfile profile,
                     List<MatchableLine> statements, List<MatchableLine> ledger, List<TruthCase> cases) {

    public Corpus {
        statements = List.copyOf(statements);
        ledger = List.copyOf(ledger);
        cases = List.copyOf(cases);
    }
}