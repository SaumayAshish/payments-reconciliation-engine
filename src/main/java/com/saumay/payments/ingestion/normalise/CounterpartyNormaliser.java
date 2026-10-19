package com.saumay.payments.ingestion.normalise;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Counterparty normalisation (FRD section 4.1): NFKC, upper-case, punctuation becomes a token break, legal-form
 * tokens are dropped, tokens are joined by single spaces. {@code "Acme Trading Ltd."} becomes
 * {@code "ACME TRADING"}. Pure and idempotent.
 */
public final class CounterpartyNormaliser {

    private static final Pattern SEPARATORS = Pattern.compile("[^A-Z0-9]+");

    /** Used for membership tests only, never iterated, so ordering cannot affect output. */
    private static final Set<String> LEGAL_FORMS =
            Set.of("LTD", "LIMITED", "PLC", "INC", "LLC", "LLP", "GMBH", "AG", "SA", "PVT", "PRIVATE");

    private CounterpartyNormaliser() {
    }

    public static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
        StringJoiner joined = new StringJoiner(" ");
        for (String token : SEPARATORS.split(folded)) {
            if (!token.isEmpty() && !LEGAL_FORMS.contains(token)) {
                joined.add(token);
            }
        }
        return joined.toString();
    }
}