package com.saumay.payments.matching;

import com.saumay.payments.domain.model.CanonicalLine;
import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineKey;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.domain.money.CurrencyCode;
import com.saumay.payments.domain.money.Money;
import java.time.LocalDate;

/** Test fixtures: small canonical lines with deterministic keys. Statement and ledger keys never collide. */
final class Lines {

    /** 2026-10-09 is a Friday. */
    static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);
    static final String ACCOUNT = "ACC-1";

    private Lines() {
    }

    static MatchableLine statement(long seq, String reference, long minorUnits, String currency,
                                   LocalDate valueDate) {
        return build(LineSource.STATEMENT, ACCOUNT, seq, reference, minorUnits, currency, valueDate, "ACME LTD",
                "INVOICE PAYMENT");
    }

    static MatchableLine ledger(long seq, String reference, long minorUnits, String currency,
                                LocalDate valueDate) {
        return build(LineSource.LEDGER, ACCOUNT, seq, reference, minorUnits, currency, valueDate, "ACME LTD",
                "INVOICE PAYMENT");
    }

    static MatchableLine build(LineSource source, String account, long seq, String reference, long minorUnits,
                               String currency, LocalDate valueDate, String counterparty, String narrative) {
        ContentHash file = ContentHash.ofUtf8(source.name() + "-file");
        CanonicalLine line = new CanonicalLine(source, account, Money.of(minorUnits, CurrencyCode.of(currency)),
                valueDate, null, reference, reference, counterparty, counterparty, narrative,
                LineKey.of(file, Math.toIntExact(seq) + 1));
        return new MatchableLine(seq, line);
    }
}