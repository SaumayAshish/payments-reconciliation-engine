package com.saumay.payments.domain.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saumay.payments.domain.cases.MatchLine.Side;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManualMatchRulesTest {

    @Test
    void oneStatementLineAgainstOneLedgerEntryIsAManualMatch() {
        assertEquals(ResolutionType.MANUAL_MATCH,
                ManualMatchRules.classify(List.of(statement(1, 3000)), List.of(ledger(2, 3000))));
    }

    @Test
    void oneStatementLineAgainstSeveralLedgerEntriesIsASplit() {
        assertEquals(ResolutionType.SPLIT, ManualMatchRules.classify(List.of(statement(1, 3000)),
                List.of(ledger(2, 1000), ledger(3, 2000))));
    }

    @Test
    void severalStatementLinesAgainstOneLedgerEntryIsASplit() {
        assertEquals(ResolutionType.SPLIT, ManualMatchRules.classify(
                List.of(statement(1, 1000), statement(2, 2000)), List.of(ledger(3, 3000))));
    }

    @Test
    void sidesThatDoNotAddUpExactlyAreRejectedNotApproximated() {
        ManualMatchRejectedException e = assertThrows(ManualMatchRejectedException.class,
                () -> ManualMatchRules.classify(List.of(statement(1, 3000)), List.of(ledger(2, 2999))));
        assertEquals("The statement side (3000) and the ledger side (2999) must add up to exactly the same amount"
                + " in minor units", e.getMessage());
    }

    @Test
    void manyToManyIsRejected() {
        assertThrows(ManualMatchRejectedException.class, () -> ManualMatchRules.classify(
                List.of(statement(1, 1000), statement(2, 2000)), List.of(ledger(3, 1000), ledger(4, 2000))));
    }

    @Test
    void aMatchNeedsBothSides() {
        assertThrows(ManualMatchRejectedException.class,
                () -> ManualMatchRules.classify(List.of(), List.of(ledger(2, 3000))));
        assertThrows(ManualMatchRejectedException.class,
                () -> ManualMatchRules.classify(List.of(statement(1, 3000)), List.of()));
    }

    @Test
    void linesInDifferentCurrenciesAreRejectedEvenWhenTheNumbersAgree() {
        assertThrows(ManualMatchRejectedException.class, () -> ManualMatchRules.classify(
                List.of(statement(1, 3000)), List.of(new MatchLine(Side.LEDGER, 2, 3000, "USD"))));
    }

    @Test
    void moreThanEightComponentsAreRejectedAndExactlyEightAreAccepted() {
        List<MatchLine> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(ledger(10 + i, 100));
        }
        assertThrows(ManualMatchRejectedException.class,
                () -> ManualMatchRules.classify(List.of(statement(1, 900)), nine));
        assertEquals(ResolutionType.SPLIT,
                ManualMatchRules.classify(List.of(statement(1, 800)), nine.subList(0, 8)));
    }

    @Test
    void theSameLineTwiceIsRejected() {
        assertThrows(ManualMatchRejectedException.class, () -> ManualMatchRules.classify(
                List.of(statement(1, 3000)), List.of(ledger(2, 1500), ledger(2, 1500))));
    }

    @Test
    void negativeAmountsBalanceAndMixedSignsDoNot() {
        assertEquals(ResolutionType.MANUAL_MATCH,
                ManualMatchRules.classify(List.of(statement(1, -3000)), List.of(ledger(2, -3000))));
        assertThrows(ManualMatchRejectedException.class,
                () -> ManualMatchRules.classify(List.of(statement(1, 3000)), List.of(ledger(2, -3000))));
    }

    @Test
    void amountsThatOverflowAreRejectedInsteadOfWrappingAround() {
        assertThrows(ManualMatchRejectedException.class, () -> ManualMatchRules.classify(
                List.of(statement(1, Long.MAX_VALUE), statement(2, 1)), List.of(ledger(3, Long.MIN_VALUE))));
    }

    @Test
    void aLineOnTheWrongSideIsRejected() {
        assertThrows(ManualMatchRejectedException.class,
                () -> ManualMatchRules.classify(List.of(ledger(1, 3000)), List.of(ledger(2, 3000))));
    }

    private static MatchLine statement(long seq, long amount) {
        return new MatchLine(Side.STATEMENT, seq, amount, "GBP");
    }

    private static MatchLine ledger(long seq, long amount) {
        return new MatchLine(Side.LEDGER, seq, amount, "GBP");
    }
}