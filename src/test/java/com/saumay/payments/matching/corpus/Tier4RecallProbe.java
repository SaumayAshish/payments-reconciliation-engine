package com.saumay.payments.matching.corpus;

import com.saumay.payments.matching.MatchingConfig;
import com.saumay.payments.matching.MatchingPipeline;
import java.util.Map;

/**
 * Prints how many one-to-many cases the engine handles as labelled when batches share accounts and dates with
 * everything else (the crowded layout), at realistic and extreme density, and with the candidate cap at 8 and 12.
 * Measurement only: run it from the command line, it asserts nothing.
 */
public final class Tier4RecallProbe {

    private static final long SEED = 20261022L;

    private Tier4RecallProbe() {
    }

    public static void main(String[] args) {
        System.out.println("layout     lines   days  cap | one-to-many ok/total | subset ok/total | truncations | false auto | failures");
        probe(CorpusGenerator.Layout.ISOLATED_BATCHES, 3000, 60, 8);
        probe(CorpusGenerator.Layout.CROWDED, 3000, 60, 8);
        probe(CorpusGenerator.Layout.CROWDED, 3000, 60, 12);
        probe(CorpusGenerator.Layout.CROWDED, 3000, 20, 8);
        probe(CorpusGenerator.Layout.CROWDED, 3000, 20, 12);
        probe(CorpusGenerator.Layout.CROWDED, 3000, 10, 8);
        probe(CorpusGenerator.Layout.CROWDED, 3000, 10, 12);
        probe(CorpusGenerator.Layout.CROWDED, 20000, 400, 8);
        probe(CorpusGenerator.Layout.CROWDED, 20000, 400, 12);
    }

    private static void probe(CorpusGenerator.Layout layout, int lines, int days, int cap) {
        Corpus corpus = CorpusGenerator.generate(SEED, lines, CorpusProfile.standard(), layout, days);
        MatchingConfig config = new MatchingConfig(950, 700, 3, 10, cap, 600, 600);
        Evaluation evaluation = CorpusEvaluator.evaluate(corpus,
                MatchingPipeline.run(corpus.statements(), corpus.ledger(), config));
        Map<Scenario, int[]> byScenario = evaluation.casesByScenario();
        int[] oneToMany = byScenario.get(Scenario.ONE_TO_MANY);
        int[] subset = byScenario.get(Scenario.SUBSET_AMBIGUITY);
        System.out.printf("%-10s %6d %5d %4d | %9d/%-9d | %7d/%-7d | %11d | %10d | %8d%n",
                layout == CorpusGenerator.Layout.CROWDED ? "CROWDED" : "ISOLATED", lines, days, cap,
                oneToMany[1], oneToMany[0], subset[1], subset[0], evaluation.truncationNotes(),
                evaluation.falseAutoMatches().size(), evaluation.failures().size());
    }
}