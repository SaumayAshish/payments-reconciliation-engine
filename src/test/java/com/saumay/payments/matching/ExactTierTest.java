package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.saumay.payments.domain.model.LineSource;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactTierTest {

    private static final LocalDate DAY = Lines.FRIDAY;

    @Test
    void pairsLinesEqualOnReferenceAmountCurrencyAndValueDate() {
        MatchableLine statement = Lines.statement(0, "INV1", 125050, "GBP", DAY);
        MatchableLine entry = Lines.ledger(0, "INV1", 125050, "GBP", DAY);

        List<ScoredPair> pairs = ExactTier.candidates(List.of(statement), List.of(entry));

        assertEquals(1, pairs.size());
        ScoredPair pair = pairs.get(0);
        assertEquals(MatchTier.EXACT, pair.tier());
        assertEquals(1000, pair.score().milli());
        assertEquals(ExactTier.REASON, pair.reason());
        assertEquals(statement, pair.statement());
        assertEquals(entry, pair.ledger());
    }

    @Test
    void anySingleDifferenceRemovesTheCandidate() {
        MatchableLine statement = Lines.statement(0, "INV1", 125050, "GBP", DAY);

        assertNoPair(statement, Lines.ledger(0, "INV2", 125050, "GBP", DAY));
        assertNoPair(statement, Lines.ledger(0, "INV1", 125051, "GBP", DAY));
        assertNoPair(statement, Lines.ledger(0, "INV1", 125050, "USD", DAY));
        assertNoPair(statement, Lines.ledger(0, "INV1", 125050, "GBP", DAY.plusDays(1)));
        assertNoPair(statement, Lines.build(LineSource.LEDGER, "ACC-2", 0, "INV1", 125050, "GBP", DAY,
                "ACME LTD", "INVOICE PAYMENT"));
    }

    @Test
    void aReversalNeverPairsWithThePaymentItReverses() {
        assertNoPair(Lines.statement(0, "INV1", 10000, "GBP", DAY), Lines.ledger(0, "INV1", -10000, "GBP", DAY));
    }

    @Test
    void negativeAmountsPairOnTheirOwnSign() {
        MatchableLine statement = Lines.statement(0, "INV1", -10000, "GBP", DAY);
        MatchableLine entry = Lines.ledger(0, "INV1", -10000, "GBP", DAY);
        assertEquals(1, ExactTier.candidates(List.of(statement), List.of(entry)).size());
    }

    @Test
    void identicalLinesOnOneSideEachFormAPairWithTheSameEntry() {
        List<MatchableLine> statements = List.of(Lines.statement(0, "INV1", 5000, "GBP", DAY),
                Lines.statement(1, "INV1", 5000, "GBP", DAY));
        List<MatchableLine> ledger = List.of(Lines.ledger(0, "INV1", 5000, "GBP", DAY));

        assertEquals(2, ExactTier.candidates(statements, ledger).size());
    }

    private static void assertNoPair(MatchableLine statement, MatchableLine entry) {
        assertEquals(List.of(), ExactTier.candidates(List.of(statement), List.of(entry)));
    }
}