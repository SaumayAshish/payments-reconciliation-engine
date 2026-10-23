package com.saumay.payments.matching.corpus;

import com.saumay.payments.domain.model.LineKey;
import com.saumay.payments.matching.AmbiguousGroup;
import com.saumay.payments.matching.Match;
import com.saumay.payments.matching.MatchBand;
import com.saumay.payments.matching.MatchTier;
import com.saumay.payments.matching.MatchableLine;
import com.saumay.payments.matching.OneToManyTier;
import com.saumay.payments.matching.PipelineResult;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares engine output with ground truth (NFR-02). A false auto-match is any auto-confirmed match that is not
 * exactly the group the truth says may be confirmed without a human; the build gate is zero of them.
 */
public final class CorpusEvaluator {

    private CorpusEvaluator() {
    }

    public static Evaluation evaluate(Corpus corpus, PipelineResult result) {
        Map<LineKey, TruthCase> caseOf = new HashMap<>();
        for (TruthCase truth : corpus.cases()) {
            for (LineKey key : truth.statements()) {
                caseOf.put(key, truth);
            }
            for (LineKey key : truth.ledger()) {
                caseOf.put(key, truth);
            }
        }
        Map<LineKey, Match> matchOf = new HashMap<>();
        for (Match match : result.matches()) {
            match.statementSide().forEach(line -> matchOf.put(line.key(), match));
            match.ledgerSide().forEach(line -> matchOf.put(line.key(), match));
        }
        Map<LineKey, AmbiguousGroup> groupOf = new HashMap<>();
        for (AmbiguousGroup group : result.ambiguous()) {
            group.statementSide().forEach(line -> groupOf.put(line.key(), group));
            group.ledgerSide().forEach(line -> groupOf.put(line.key(), group));
        }

        List<String> falseAuto = new ArrayList<>();
        int autoMatches = 0;
        int correctAutoStatementLines = 0;
        int reviewMatches = 0;
        int incorrectReview = 0;
        Map<MatchTier, int[]> byTier = new EnumMap<>(MatchTier.class);
        for (Match match : result.matches()) {
            int[] counts = byTier.computeIfAbsent(match.tier(), t -> new int[2]);
            TruthCase truth = caseOf.get(match.statementSide().get(0).key());
            boolean agrees = truth != null && truth.expected().isMatch() && sameLines(match, truth)
                    && Evaluation.tierOf(truth.expected()) == match.tier();
            if (match.band() == MatchBand.AUTO_CONFIRM) {
                counts[0]++;
                autoMatches++;
                if (agrees && truth.expected().isAuto()) {
                    correctAutoStatementLines += match.statementSide().size();
                } else {
                    falseAuto.add(describe(match, truth));
                }
            } else {
                counts[1]++;
                reviewMatches++;
                if (!agrees) {
                    incorrectReview++;
                }
            }
        }

        Map<Scenario, int[]> byScenario = new EnumMap<>(Scenario.class);
        List<String> failures = new ArrayList<>();
        for (TruthCase truth : corpus.cases()) {
            int[] counts = byScenario.computeIfAbsent(truth.scenario(), s -> new int[2]);
            counts[0]++;
            if (satisfied(truth, matchOf, groupOf)) {
                counts[1]++;
            } else if (failures.size() < 25) {
                failures.add("case " + truth.id() + " " + truth.scenario() + " expected " + truth.expected()
                        + " but " + actual(truth, matchOf, groupOf));
            }
        }
        int truncations = 0;
        for (String note : result.notes()) {
            if (note.startsWith(OneToManyTier.TRUNCATED_NOTE)) {
                truncations++;
            }
        }
        return new Evaluation(corpus.seed(), corpus.statements().size(), corpus.ledger().size(), autoMatches,
                falseAuto, correctAutoStatementLines, reviewMatches, incorrectReview, result.ambiguous().size(),
                truncations, byTier, byScenario, failures);
    }

    private static boolean satisfied(TruthCase truth, Map<LineKey, Match> matchOf,
                                     Map<LineKey, AmbiguousGroup> groupOf) {
        List<LineKey> all = allKeys(truth);
        return switch (truth.expected()) {
            case UNMATCHED -> all.stream().noneMatch(k -> matchOf.containsKey(k) || groupOf.containsKey(k));
            case AMBIGUOUS -> {
                AmbiguousGroup group = groupOf.get(all.get(0));
                yield group != null && keySet(group.statementSide()).equals(new TreeSet<>(truth.statements()))
                        && keySet(group.ledgerSide()).equals(new TreeSet<>(truth.ledger()))
                        && all.stream().noneMatch(matchOf::containsKey);
            }
            default -> {
                Match match = matchOf.get(all.get(0));
                yield match != null && sameLines(match, truth)
                        && match.tier() == Evaluation.tierOf(truth.expected())
                        && match.band() == Evaluation.bandOf(truth.expected());
            }
        };
    }

    private static String actual(TruthCase truth, Map<LineKey, Match> matchOf,
                                 Map<LineKey, AmbiguousGroup> groupOf) {
        StringBuilder text = new StringBuilder();
        for (LineKey key : allKeys(truth)) {
            Match match = matchOf.get(key);
            AmbiguousGroup group = groupOf.get(key);
            text.append(key.value(), 0, 8).append(':');
            if (match != null) {
                text.append(match.tier()).append('/').append(match.band()).append(' ')
                        .append(match.statementSide().size()).append('x').append(match.ledgerSide().size());
            } else if (group != null) {
                text.append("AMBIGUOUS ").append(group.statementSide().size()).append('x')
                        .append(group.ledgerSide().size());
            } else {
                text.append("unmatched");
            }
            text.append("; ");
        }
        return text.toString();
    }

    private static String describe(Match match, TruthCase truth) {
        return match.tier() + " " + match.score() + " " + match.statementSide().size() + "x"
                + match.ledgerSide().size() + " truth="
                + (truth == null ? "none" : truth.scenario() + "/" + truth.expected()) + " " + match.reason();
    }

    private static boolean sameLines(Match match, TruthCase truth) {
        return keySet(match.statementSide()).equals(new TreeSet<>(truth.statements()))
                && keySet(match.ledgerSide()).equals(new TreeSet<>(truth.ledger()));
    }

    private static Set<LineKey> keySet(List<MatchableLine> lines) {
        Set<LineKey> keys = new TreeSet<>();
        lines.forEach(line -> keys.add(line.key()));
        return keys;
    }

    private static List<LineKey> allKeys(TruthCase truth) {
        List<LineKey> all = new ArrayList<>(truth.statements());
        all.addAll(truth.ledger());
        return all;
    }
}