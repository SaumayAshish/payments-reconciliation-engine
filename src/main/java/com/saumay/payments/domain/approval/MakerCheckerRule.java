package com.saumay.payments.domain.approval;

import java.util.Locale;
import java.util.Objects;

/** Separation of duties (FRD-08). Actor ids compare case-insensitively, exactly as the database CHECK does. */
public final class MakerCheckerRule {

    private MakerCheckerRule() {
    }

    public static boolean sameActor(String maker, String checker) {
        Objects.requireNonNull(maker, "maker");
        Objects.requireNonNull(checker, "checker");
        return maker.trim().toLowerCase(Locale.ROOT).equals(checker.trim().toLowerCase(Locale.ROOT));
    }

    /** Returns normally when the two are different people, otherwise throws {@link SeparationOfDutiesException}. */
    public static void requireDistinct(String maker, String checker) {
        if (sameActor(maker, checker)) {
            throw new SeparationOfDutiesException();
        }
    }
}