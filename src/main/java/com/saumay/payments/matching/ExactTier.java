package com.saumay.payments.matching;

import com.saumay.payments.domain.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier 1 (FRD-04): same account, normalised reference, amount, currency and value date. Score 1.000. The amount
 * includes its sign, so a reversal never pairs with the payment it reverses (EC-04.4).
 */
public final class ExactTier {

    public static final String REASON = "EXACT: reference, amount, currency, value date equal";

    private record Key(String account, String referenceNorm, Money amount, LocalDate valueDate) {

        static Key of(MatchableLine line) {
            return new Key(line.line().account(), line.line().referenceNorm(), line.line().amount(),
                    line.line().valueDate());
        }
    }

    private ExactTier() {
    }

    public static List<ScoredPair> candidates(List<MatchableLine> statements, List<MatchableLine> ledger) {
        Map<Key, List<MatchableLine>> index = new HashMap<>();
        for (MatchableLine entry : ledger) {
            index.computeIfAbsent(Key.of(entry), k -> new ArrayList<>()).add(entry);
        }
        MatchScore score = MatchScore.ofMilli(MatchTier.EXACT.ceilingMilli());
        List<ScoredPair> pairs = new ArrayList<>();
        for (MatchableLine statement : statements) {
            for (MatchableLine entry : index.getOrDefault(Key.of(statement), List.of())) {
                pairs.add(new ScoredPair(statement, entry, MatchTier.EXACT, score, REASON));
            }
        }
        return pairs;
    }
}