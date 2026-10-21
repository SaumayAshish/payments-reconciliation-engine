package com.saumay.payments.matching;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Integer text similarity for the fuzzy tier (FRD-04), all on a 0 to 1000 scale so results are exact and
 * repeatable. Every function is symmetric and has no hidden state.
 */
final class Similarity {

    private static final Pattern SEPARATORS = Pattern.compile("[^A-Z0-9]+");

    private Similarity() {
    }

    /**
     * Jaccard similarity of the whitespace-separated token sets of two already-normalised counterparties. If
     * either side has no tokens there is no evidence, so the result is 0.
     */
    static int tokenSetMilli(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0;
        }
        int common = 0;
        for (String token : leftTokens) {
            if (rightTokens.contains(token)) {
                common++;
            }
        }
        int union = leftTokens.size() + rightTokens.size() - common;
        return common * MatchScore.SCALE / union;
    }

    /**
     * One minus the edit distance divided by the longer length, after upper-casing and reducing punctuation and
     * runs of spaces. Two empty narratives carry no evidence, so the result is 0.
     */
    static int editSimilarityMilli(String left, String right) {
        String a = clean(left);
        String b = clean(right);
        int longer = Math.max(a.length(), b.length());
        if (longer == 0) {
            return 0;
        }
        return (longer - editDistance(a, b)) * MatchScore.SCALE / longer;
    }

    /** The weighted blend of the two similarities; the counterparty weight comes from configuration. */
    static int blendMilli(int counterpartyMilli, int narrativeMilli, int counterpartyWeightMilli) {
        return (counterpartyWeightMilli * counterpartyMilli
                + (MatchScore.SCALE - counterpartyWeightMilli) * narrativeMilli) / MatchScore.SCALE;
    }

    /** Classic Levenshtein distance with two rolling rows. */
    static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static Set<String> tokens(String normalised) {
        Set<String> result = new TreeSet<>();
        for (String token : normalised.split(" ")) {
            if (!token.isEmpty()) {
                result.add(token);
            }
        }
        return result;
    }

    private static String clean(String text) {
        return SEPARATORS.matcher(text.toUpperCase(Locale.ROOT)).replaceAll(" ").trim();
    }
}