package com.saumay.payments.ingestion.normalise;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reference normalisation (FRD section 4.1): Unicode NFKC, upper-case with {@link Locale#ROOT}, then remove
 * every character outside {@code A-Z0-9}. Leading zeros are preserved because they can be significant.
 *
 * <p>NFKC folds full-width digits and compatibility forms to ASCII; the character filter removes zero-width
 * characters and look-alike punctuation (FRD EC-02.5). The function is pure and idempotent.
 */
public final class ReferenceNormaliser {

    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^A-Z0-9]");

    private ReferenceNormaliser() {
    }

    public static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
        return NOT_ALPHANUMERIC.matcher(folded).replaceAll("");
    }
}