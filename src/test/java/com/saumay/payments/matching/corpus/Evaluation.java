package com.saumay.payments.matching.corpus;

import com.saumay.payments.matching.MatchBand;
import com.saumay.payments.matching.MatchTier;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** How the engine's output compares with the ground truth of one corpus. */
public record Evaluation(long seed, int statementLines, int ledgerEntries, int autoMatches,
                         List<String> falseAutoMatches, int correctAutoStatementLines, int reviewMatches,
                         int incorrectReviewMatches, int ambiguousGroups, int truncationNotes,
                         Map<MatchTier, int[]> matchesByTier, Map<Scenario, int[]> casesByScenario, List<String> failures) {

    public Evaluation {
        falseAutoMatches = List.copyOf(falseAutoMatches);
        failures = List.copyOf(failures);
    }

    /** Q-02: auto-confirmed statement lines (correctly) over all statement lines, in parts per thousand. */
    public int autoMatchRatePermille() {
        return correctAutoStatementLines * 1000 / statementLines;
    }

    public int casesPassed() {
        int passed = 0;
        for (int[] counts : casesByScenario.values()) {
            passed += counts[1];
        }
        return passed;
    }

    public int casesTotal() {
        int total = 0;
        for (int[] counts : casesByScenario.values()) {
            total += counts[0];
        }
        return total;
    }

    private static String percent(int permille) {
        return String.format(Locale.ROOT, "%d.%d%%", permille / 10, permille % 10);
    }

    public String summary() {
        StringBuilder text = new StringBuilder();
        text.append("seed=").append(seed).append(" statementLines=").append(statementLines)
                .append(" ledgerEntries=").append(ledgerEntries).append('\n');
        text.append("auto-confirmed matches=").append(autoMatches).append(" false auto-matches=")
                .append(falseAutoMatches.size()).append('\n');
        text.append("auto match rate (Q-02)=").append(percent(autoMatchRatePermille())).append('\n');
        text.append("review matches=").append(reviewMatches).append(" (incorrect proposals=")
                .append(incorrectReviewMatches).append(") ambiguous groups=").append(ambiguousGroups)
                .append(" tier-4 truncation notes=").append(truncationNotes).append('\n');
        for (MatchTier tier : MatchTier.values()) {
            int[] counts = matchesByTier.getOrDefault(tier, new int[2]);
            text.append(String.format(Locale.ROOT, "  %-12s auto=%d review=%d%n", tier, counts[0], counts[1]));
        }
        text.append("truth cases passed=").append(casesPassed()).append('/').append(casesTotal()).append('\n');
        for (Map.Entry<Scenario, int[]> entry : new EnumMap<>(casesByScenario).entrySet()) {
            text.append(String.format(Locale.ROOT, "  %-28s %d/%d%n", entry.getKey(), entry.getValue()[1],
                    entry.getValue()[0]));
        }
        for (String failure : failures) {
            text.append("FAIL ").append(failure).append('\n');
        }
        for (String failure : falseAutoMatches) {
            text.append("FALSE AUTO ").append(failure).append('\n');
        }
        return text.toString();
    }

    static MatchTier tierOf(Expected expected) {
        return switch (expected) {
            case AUTO_EXACT -> MatchTier.EXACT;
            case AUTO_TOLERANCE, REVIEW_TOLERANCE -> MatchTier.TOLERANCE;
            case REVIEW_FUZZY -> MatchTier.FUZZY;
            case REVIEW_ONE_TO_MANY -> MatchTier.ONE_TO_MANY;
            default -> throw new IllegalArgumentException(expected + " is not a match expectation");
        };
    }

    static MatchBand bandOf(Expected expected) {
        return expected.isAuto() ? MatchBand.AUTO_CONFIRM : MatchBand.REVIEW;
    }
}