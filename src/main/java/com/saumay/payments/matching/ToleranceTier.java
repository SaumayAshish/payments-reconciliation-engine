package com.saumay.payments.matching;

import com.saumay.payments.domain.model.BusinessCalendar;
import com.saumay.payments.domain.money.Money;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier 2 (FRD-04): same account, normalised reference, amount and currency, but value dates that differ by at
 * most the configured number of business days. A weekend date counts as the following Monday, so a Saturday
 * and that Monday are zero business days apart (EC-04.6) and still reach this tier because the dates differ.
 *
 * <p>Score: the tier ceiling minus a penalty that grows linearly to the tier floor at the window edge,
 * computed in integers and rounded half up.
 */
public final class ToleranceTier {

    private record Key(String account, String referenceNorm, Money amount) {

        static Key of(MatchableLine line) {
            return new Key(line.line().account(), line.line().referenceNorm(), line.line().amount());
        }
    }

    private ToleranceTier() {
    }

    public static List<ScoredPair> candidates(List<MatchableLine> statements, List<MatchableLine> ledger,
                                              MatchingConfig config) {
        Map<Key, List<MatchableLine>> index = new HashMap<>();
        for (MatchableLine entry : ledger) {
            index.computeIfAbsent(Key.of(entry), k -> new ArrayList<>()).add(entry);
        }
        int window = config.dateWindowBusinessDays();
        List<ScoredPair> pairs = new ArrayList<>();
        for (MatchableLine statement : statements) {
            for (MatchableLine entry : index.getOrDefault(Key.of(statement), List.of())) {
                if (statement.line().valueDate().equals(entry.line().valueDate())) {
                    continue;
                }
                int distance = BusinessCalendar.businessDaysBetween(statement.line().valueDate(),
                        entry.line().valueDate());
                if (distance > window) {
                    continue;
                }
                pairs.add(new ScoredPair(statement, entry, MatchTier.TOLERANCE,
                        MatchScore.ofMilli(scoreFor(distance, window)), reason(distance)));
            }
        }
        return pairs;
    }

    static int scoreFor(int distance, int window) {
        int span = MatchTier.TOLERANCE.ceilingMilli() - MatchTier.TOLERANCE.floorMilli();
        int penalty = (2 * span * distance + window) / (2 * window);
        return MatchTier.TOLERANCE.ceilingMilli() - penalty;
    }

    static String reason(int distance) {
        return "TOLERANCE: reference, amount, currency equal; value dates " + distance + " business day(s) apart";
    }
}