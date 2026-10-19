package com.saumay.payments.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.money.CurrencyCode;
import com.saumay.payments.domain.money.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CanonicalLineTest {

    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final LineKey KEY = LineKey.of(ContentHash.ofUtf8("file"), 1);

    private static CanonicalLine line(String account, Money amount, String reference, String referenceNorm,
                                      String narrative) {
        return new CanonicalLine(LineSource.STATEMENT, account, amount, LocalDate.of(2026, 10, 9), null,
                reference, referenceNorm, "", "", narrative, KEY);
    }

    private static CanonicalLine valid() {
        return line("NOSTRO-001", Money.of(100, GBP), "INV-1", "INV1", "");
    }

    @Test
    void acceptsAValidLineAndKeepsBookingDateNullable() {
        CanonicalLine line = valid();
        assertEquals("NOSTRO-001", line.account());
        assertNull(line.bookingDate());
    }

    @Test
    void rejectsAnAccountOutsideTheAllowedAlphabetOrLength() {
        assertThrows(IllegalArgumentException.class,
                () -> line("nostro", Money.of(100, GBP), "INV-1", "INV1", ""));
        assertThrows(IllegalArgumentException.class,
                () -> line("", Money.of(100, GBP), "INV-1", "INV1", ""));
        assertThrows(IllegalArgumentException.class,
                () -> line("A".repeat(35), Money.of(100, GBP), "INV-1", "INV1", ""));
    }

    @Test
    void rejectsAZeroAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> line("NOSTRO-001", Money.zero(GBP), "INV-1", "INV1", ""));
    }

    @Test
    void rejectsABlankReferenceOrAnEmptyNormalisedReference() {
        assertThrows(IllegalArgumentException.class,
                () -> line("NOSTRO-001", Money.of(100, GBP), "   ", "INV1", ""));
        assertThrows(IllegalArgumentException.class,
                () -> line("NOSTRO-001", Money.of(100, GBP), "---", "", ""));
    }

    @Test
    void rejectsANarrativeLongerThanTheLimitButAcceptsExactlyTheLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> line("NOSTRO-001", Money.of(100, GBP), "INV-1", "INV1",
                        "x".repeat(CanonicalLine.MAX_NARRATIVE_LENGTH + 1)));
        assertEquals(CanonicalLine.MAX_NARRATIVE_LENGTH,
                line("NOSTRO-001", Money.of(100, GBP), "INV-1", "INV1",
                        "x".repeat(CanonicalLine.MAX_NARRATIVE_LENGTH)).narrative().length());
    }

    @Test
    void rejectsNullsInRequiredFields() {
        assertThrows(NullPointerException.class,
                () -> new CanonicalLine(null, "A1", Money.of(1, GBP), LocalDate.of(2026, 10, 9), null,
                        "R", "R", "", "", "", KEY));
        assertThrows(NullPointerException.class,
                () -> new CanonicalLine(LineSource.LEDGER, "A1", Money.of(1, GBP), null, null,
                        "R", "R", "", "", "", KEY));
        assertThrows(NullPointerException.class,
                () -> new CanonicalLine(LineSource.LEDGER, "A1", Money.of(1, GBP), LocalDate.of(2026, 10, 9), null,
                        "R", "R", null, "", "", KEY));
    }

    @Test
    void lineKeysSortByTheirHexValueAndPrintAsTheirHex() {
        LineKey a = LineKey.of(ContentHash.ofUtf8("file"), 1);
        LineKey b = LineKey.of(ContentHash.ofUtf8("file"), 2);
        assertEquals(Integer.signum(a.value().compareTo(b.value())), Integer.signum(a.compareTo(b)));
        assertEquals(a.value(), a.toString());
        assertTrue(ContentHash.ofUtf8("abc").toString().startsWith("ba7816bf"));
    }
}