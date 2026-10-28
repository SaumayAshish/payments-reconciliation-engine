package com.saumay.payments.domain.cases;

import java.util.Objects;

/** One line as a manual match sees it: its side, its signed amount in minor units and its currency. */
public record MatchLine(Side side, long seq, long amountMinor, String currency) {

    public enum Side {
        STATEMENT,
        LEDGER
    }

    public MatchLine {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(currency, "currency");
    }
}