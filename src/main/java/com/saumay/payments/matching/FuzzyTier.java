package com.saumay.payments.matching;

import com.saumay.payments.domain.model.BusinessCalendar;
import com.saumay.payments.domain.money.Money;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier 3 (FRD-04): same account, amount and currency, value dates within the configured window, and text that is
 * similar enough. The references may differ; that is the point of this tier. The amount, currency and date
 * conditions are never relaxed (Q-04), so a fuzzy match cannot pair different money.
 *
 * <p>{@code similarity = w * counterpartyTokenSimilarity + (1 - w) * narrativeEditSimilarity}, with the weight and
 * the minimum taken from configuration. The score maps the range from the minimum similarity to 1 linearly onto
 * the tier range, ending at 0.949, so under default thresholds a fuzzy match is always reviewed (Q-03).
 */
public final class FuzzyTier {

    private record Key(String account, Money amount) {

        static Key of(MatchableLine line) {
            return new Key(line.line().account(), line.line().amount());
        }
    }

    private FuzzyTier() {
    }

    public static List<ScoredPair> candidates(List<MatchableLine> statements, List<MatchableLine> ledger,
                                              MatchingConfig config) {
        Map<Key, List<MatchableLine>> index = new HashMap<>();
        for (MatchableLine entry : ledger) {
            index.computeIfAbsent(Key.of(entry), k -> new ArrayList<>()).add(entry);
        }
        List<ScoredPair> pairs = new ArrayList<>();
        for (MatchableLine statement : statements) {
            for (MatchableLine entry : index.getOrDefault(Key.of(statement), List.of())) {
                int distance = BusinessCalendar.businessDaysBetween(statement.line().valueDate(),
                        entry.line().valueDate());
                if (distance > config.dateWindowBusinessDays()) {
                    continue;
                }
                int counterparty = Similarity.tokenSetMilli(statement.line().counterpartyNorm(),
                        entry.line().counterpartyNorm());
                int narrative = Similarity.editSimilarityMilli(statement.line().narrative(),
                        entry.line().narrative());
                int similarity = Similarity.blendMilli(counterparty, narrative,
                        config.fuzzyCounterpartyWeightMilli());
                if (similarity < config.fuzzyMinSimilarityMilli()) {
                    continue;
                }
                pairs.add(new ScoredPair(statement, entry, MatchTier.FUZZY,
                        MatchScore.ofMilli(scoreFor(similarity, config)), reason(counterparty, narrative, distance)));
            }
        }
        return pairs;
    }

    /** Linear map of [minimum similarity, 1] onto the tier range, in integers, rounded half up. */
    static int scoreFor(int similarityMilli, MatchingConfig config) {
        int span = MatchTier.FUZZY.ceilingMilli() - MatchTier.FUZZY.floorMilli();
        int denominator = MatchScore.SCALE - config.fuzzyMinSimilarityMilli();
        int numerator = similarityMilli - config.fuzzyMinSimilarityMilli();
        return MatchTier.FUZZY.floorMilli() + (2 * numerator * span + denominator) / (2 * denominator);
    }

    static String reason(int counterpartyMilli, int narrativeMilli, int distance) {
        return "FUZZY: amount, currency equal; counterparty similarity " + MatchScore.ofMilli(counterpartyMilli)
                + ", narrative similarity " + MatchScore.ofMilli(narrativeMilli) + ", value dates " + distance
                + " business day(s) apart";
    }
}