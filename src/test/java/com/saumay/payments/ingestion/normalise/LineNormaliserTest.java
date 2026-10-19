package com.saumay.payments.ingestion.normalise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saumay.payments.domain.model.CanonicalLine;
import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineKey;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.domain.money.CurrencyCode;
import com.saumay.payments.domain.money.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LineNormaliserTest {

    private static final ContentHash FILE = ContentHash.ofUtf8("statement-file");

    private static RawLine valid() {
        return new RawLine("nostro-gbp-001", "2026-10-08", "2026-10-09", "1250.50", "gbp",
                " INV-2026/0042 ", "Acme Trading Ltd.", "Payment for invoice");
    }

    private static LineErrorCode rejectionOf(RawLine raw) {
        return assertThrows(LineValidationException.class,
                () -> LineNormaliser.normalise(LineSource.STATEMENT, FILE, 1, raw)).code();
    }

    @Test
    void producesTheCanonicalRecordDescribedInTheFrd() {
        CanonicalLine line = LineNormaliser.normalise(LineSource.STATEMENT, FILE, 7, valid());

        assertEquals(LineSource.STATEMENT, line.source());
        assertEquals("NOSTRO-GBP-001", line.account());
        assertEquals(Money.of(125050, CurrencyCode.of("GBP")), line.amount());
        assertEquals(LocalDate.of(2026, 10, 9), line.valueDate());
        assertEquals(LocalDate.of(2026, 10, 8), line.bookingDate());
        assertEquals("INV-2026/0042", line.reference());
        assertEquals("INV20260042", line.referenceNorm());
        assertEquals("ACME TRADING", line.counterpartyNorm());
        assertEquals(LineKey.of(FILE, 7), line.lineKey());
    }

    @Test
    void sameInputGivesIdenticalOutput() {
        assertEquals(LineNormaliser.normalise(LineSource.LEDGER, FILE, 1, valid()),
                LineNormaliser.normalise(LineSource.LEDGER, FILE, 1, valid()));
    }

    @Test
    void weekendValueDateIsAcceptedAndMissingBookingDateStaysNull() {
        RawLine raw = new RawLine("A1", null, "2026-10-10", "5", "GBP", "R1", null, null);
        CanonicalLine line = LineNormaliser.normalise(LineSource.LEDGER, FILE, 2, raw);
        assertEquals(LocalDate.of(2026, 10, 10), line.valueDate());
        assertNull(line.bookingDate());
        assertEquals("", line.counterparty());
        assertEquals("", line.narrative());
    }

    @Test
    void negativeAmountsAreValidMoneyOut() {
        RawLine raw = new RawLine("A1", null, "2026-10-09", "-12.00", "GBP", "R1", null, null);
        assertEquals(-1200, LineNormaliser.normalise(LineSource.STATEMENT, FILE, 1, raw).amount().minorUnits());
    }

    @Test
    void rejectsEachInvalidFieldWithItsOwnErrorCode() {
        RawLine v = valid();
        assertEquals(LineErrorCode.INVALID_ACCOUNT, rejectionOf(new RawLine("", v.bookingDate(), v.valueDate(),
                v.amount(), v.currency(), v.reference(), v.counterparty(), v.narrative())));
        assertEquals(LineErrorCode.INVALID_CURRENCY, rejectionOf(new RawLine(v.account(), v.bookingDate(),
                v.valueDate(), v.amount(), "ZZZ", v.reference(), v.counterparty(), v.narrative())));
        assertEquals(LineErrorCode.INVALID_DATE, rejectionOf(new RawLine(v.account(), v.bookingDate(), "2026-02-30",
                v.amount(), v.currency(), v.reference(), v.counterparty(), v.narrative())));
        assertEquals(LineErrorCode.INVALID_DATE, rejectionOf(new RawLine(v.account(), v.bookingDate(), "09/10/2026",
                v.amount(), v.currency(), v.reference(), v.counterparty(), v.narrative())));
        assertEquals(LineErrorCode.INVALID_DATE, rejectionOf(new RawLine(v.account(), v.bookingDate(), null,
                v.amount(), v.currency(), v.reference(), v.counterparty(), v.narrative())));
        assertEquals(LineErrorCode.AMOUNT_ZERO, rejectionOf(new RawLine(v.account(), v.bookingDate(), v.valueDate(),
                "0.00", v.currency(), v.reference(), v.counterparty(), v.narrative())));
        assertEquals(LineErrorCode.AMOUNT_PRECISION, rejectionOf(new RawLine(v.account(), v.bookingDate(),
                v.valueDate(), "10.005", v.currency(), v.reference(), v.counterparty(), v.narrative())));
        assertEquals(LineErrorCode.MISSING_REFERENCE, rejectionOf(new RawLine(v.account(), v.bookingDate(),
                v.valueDate(), v.amount(), v.currency(), "  ", v.counterparty(), v.narrative())));
        assertEquals(LineErrorCode.MISSING_REFERENCE, rejectionOf(new RawLine(v.account(), v.bookingDate(),
                v.valueDate(), v.amount(), v.currency(), "---", v.counterparty(), v.narrative())));
        assertEquals(LineErrorCode.NARRATIVE_TOO_LONG, rejectionOf(new RawLine(v.account(), v.bookingDate(),
                v.valueDate(), v.amount(), v.currency(), v.reference(), v.counterparty(), "x".repeat(281))));
    }
}