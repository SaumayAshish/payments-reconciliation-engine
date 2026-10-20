package com.saumay.payments.ingestion;

import com.saumay.payments.domain.model.CanonicalLine;
import java.time.LocalDate;

/** The business content of a ledger entry, used to tell a harmless repeat from a conflicting reuse of an entry id. */
public record EntryContent(
        String account,
        String currency,
        long minorUnits,
        LocalDate valueDate,
        LocalDate bookingDate,
        String reference,
        String counterparty,
        String narrative) {

    public static EntryContent of(CanonicalLine line) {
        return new EntryContent(
                line.account(),
                line.amount().currency().code(),
                line.amount().minorUnits(),
                line.valueDate(),
                line.bookingDate(),
                line.reference(),
                line.counterparty(),
                line.narrative());
    }
}