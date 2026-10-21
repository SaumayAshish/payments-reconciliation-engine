package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.model.LineSource;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FuzzyTierTest {

    private static final MatchingConfig DEFAULTS = MatchingConfig.defaults();
    private static final LocalDate FRIDAY = Lines.FRIDAY;
    private static final LocalDate MONDAY = FRIDAY.plusDays(3);
    private static final String ACME = "ACME TRADING";
    private static final String PAYMENT = "INVOICE 42 PAYMENT";

    private static MatchableLine statement(long seq, String reference, String counterparty, String narrative,
                                           LocalDate date) {
        return Lines.build(LineSource.STATEMENT, Lines.ACCOUNT, seq, reference, 125050, "GBP", date,
                counterparty, narrative);
    }

    private static MatchableLine entry(long seq, String reference, String counterparty, String narrative,
                                       LocalDate date) {
        return Lines.build(LineSource.LEDGER, Lines.ACCOUNT, seq, reference, 125050, "GBP", date,
                counterparty, narrative);
    }

    private static List<ScoredPair> pairs(MatchableLine statement, MatchableLine entry) {
        return FuzzyTier.candidates(List.of(statement), List.of(entry), DEFAULTS);
    }

    @Test
    void identicalTextWithDifferentReferencesScoresTheTierCeiling() {
        List<ScoredPair> pairs = pairs(statement(0, "INV1", ACME, PAYMENT, FRIDAY),
                entry(0, "PAY9", ACME, PAYMENT, FRIDAY));

        assertEquals(1, pairs.size());
        assertEquals(MatchTier.FUZZY, pairs.get(0).tier());
        assertEquals(949, pairs.get(0).score().milli());
        assertTrue(pairs.get(0).reason().startsWith("FUZZY:"));
        assertTrue(pairs.get(0).reason().contains("counterparty similarity 1.000"));
    }

    @Test
    void similarTextScoresBetweenTheFloorAndTheCeiling() {
        // counterparty 1.000, narrative 0.833, blend 0.933
        List<ScoredPair> pairs = pairs(statement(0, "INV1", ACME, PAYMENT, FRIDAY),
                entry(0, "PAY9", ACME, "INVOICE 42 PYMT", FRIDAY));

        assertEquals(1, pairs.size());
        assertEquals(907, pairs.get(0).score().milli());
        assertTrue(pairs.get(0).reason().contains("narrative similarity 0.833"));
    }

    @Test
    void reasonNamesTheDateDistance() {
        List<ScoredPair> pairs = pairs(statement(0, "INV1", ACME, PAYMENT, MONDAY.plusDays(1)),
                entry(0, "PAY9", ACME, PAYMENT, FRIDAY));
        assertTrue(pairs.get(0).reason().contains("2 business day(s)"));
    }

    @Test
    void aFuzzyMatchIsReviewedByDefaultButCanAutoConfirmWhenTheThresholdIsLoweredOnPurpose() {
        MatchableLine statement = statement(0, "INV1", ACME, PAYMENT, FRIDAY);
        MatchableLine entry = entry(0, "PAY9", ACME, PAYMENT, FRIDAY);
        List<MatchableLine> statements = List.of(statement);
        List<MatchableLine> ledger = List.of(entry);

        Match byDefault = PairResolver.resolve(statements, ledger,
                FuzzyTier.candidates(statements, ledger, DEFAULTS), DEFAULTS).matches().get(0);
        MatchingConfig lowered = DEFAULTS.withThresholds(900, 600);
        Match afterLowering = PairResolver.resolve(statements, ledger,
                FuzzyTier.candidates(statements, ledger, lowered), lowered).matches().get(0);

        assertEquals(MatchBand.REVIEW, byDefault.band());
        assertEquals(MatchBand.AUTO_CONFIRM, afterLowering.band());
    }

    @Test
    void lookAlikesThatMustNotMatchProduceNoCandidate() {
        MatchableLine statement = statement(0, "INV1", ACME, PAYMENT, FRIDAY);
        // different counterparty and narrative entirely
        assertEquals(List.of(), pairs(statement, entry(0, "PAY9", "ZENITH FREIGHT", "SALARY OCTOBER", FRIDAY)));
        // same narrative but a different company
        assertEquals(List.of(), pairs(statement, entry(0, "PAY9", "ZENITH FREIGHT", PAYMENT, FRIDAY)));
        // a look-alike company name with an unrelated narrative
        assertEquals(List.of(), pairs(statement, entry(0, "PAY9", "ACME TRADERS", "SALARY OCTOBER", FRIDAY)));
    }

    @Test
    void amountCurrencyAccountAndDateWindowAreNeverRelaxed() {
        MatchableLine statement = statement(0, "INV1", ACME, PAYMENT, FRIDAY);

        assertEquals(List.of(), pairs(statement, Lines.build(LineSource.LEDGER, Lines.ACCOUNT, 0, "PAY9", 125051,
                "GBP", FRIDAY, ACME, PAYMENT)));
        assertEquals(List.of(), pairs(statement, Lines.build(LineSource.LEDGER, Lines.ACCOUNT, 0, "PAY9", 125050,
                "USD", FRIDAY, ACME, PAYMENT)));
        assertEquals(List.of(), pairs(statement, Lines.build(LineSource.LEDGER, "ACC-2", 0, "PAY9", 125050,
                "GBP", FRIDAY, ACME, PAYMENT)));
        assertEquals(List.of(), pairs(statement, Lines.build(LineSource.LEDGER, Lines.ACCOUNT, 0, "PAY9", -125050,
                "GBP", FRIDAY, ACME, PAYMENT)));
        assertEquals(List.of(), pairs(statement, entry(0, "PAY9", ACME, PAYMENT, FRIDAY.plusDays(6))));
        assertEquals(1, pairs(statement, entry(0, "PAY9", ACME, PAYMENT, FRIDAY.plusDays(5))).size());
    }

    @Test
    void similarityExactlyAtTheMinimumIsAdmittedAtTheTierFloor() {
        // identical counterparty, no narrative on either side: 0.6 * 1.000 + 0.4 * 0 = 0.600
        List<ScoredPair> pairs = pairs(statement(0, "INV1", ACME, "", FRIDAY), entry(0, "PAY9", ACME, "", FRIDAY));

        assertEquals(1, pairs.size());
        assertEquals(700, pairs.get(0).score().milli());
    }

    @Test
    void similarityBelowTheMinimumIsRejected() {
        // two of three tokens shared (0.666) and no narrative: 0.399
        assertEquals(List.of(), pairs(statement(0, "INV1", ACME, "", FRIDAY),
                entry(0, "PAY9", "ACME TRADING LONDON", "", FRIDAY)));
    }

    @Test
    void theCounterpartyWeightComesFromConfiguration() {
        MatchableLine statement = statement(0, "INV1", ACME, PAYMENT, FRIDAY);
        MatchableLine entry = entry(0, "PAY9", "ZENITH FREIGHT", PAYMENT, FRIDAY);
        MatchingConfig narrativeOnly = new MatchingConfig(950, 700, 3, 10, 8, 600, 0);

        assertEquals(List.of(), FuzzyTier.candidates(List.of(statement), List.of(entry), DEFAULTS));
        assertEquals(1, FuzzyTier.candidates(List.of(statement), List.of(entry), narrativeOnly).size());
    }

    @Test
    void scoreMapsTheSimilarityRangeLinearlyOntoTheTierRange() {
        assertEquals(700, FuzzyTier.scoreFor(600, DEFAULTS));
        assertEquals(762, FuzzyTier.scoreFor(700, DEFAULTS));
        assertEquals(949, FuzzyTier.scoreFor(1000, DEFAULTS));
        for (int minimum = 100; minimum <= 900; minimum += 100) {
            MatchingConfig config = new MatchingConfig(950, 700, 3, 10, 8, minimum, 600);
            int previous = 0;
            for (int similarity = minimum; similarity <= MatchScore.SCALE; similarity++) {
                int milli = FuzzyTier.scoreFor(similarity, config);
                assertTrue(MatchTier.FUZZY.allows(MatchScore.ofMilli(milli)), "minimum " + minimum);
                assertTrue(milli >= previous, "score must not fall as similarity rises");
                previous = milli;
            }
        }
    }

    @Test
    void sameAmountCollisionsAreResolvedByTheTextNotByAccident() {
        MatchableLine acme = statement(0, "INV1", ACME, PAYMENT, FRIDAY);
        MatchableLine zenith = statement(1, "INV2", "ZENITH FREIGHT", "FREIGHT SURCHARGE", FRIDAY);
        MatchableLine acmeEntry = entry(0, "PAY1", ACME, PAYMENT, FRIDAY);
        MatchableLine zenithEntry = entry(1, "PAY2", "ZENITH FREIGHT", "FREIGHT SURCHARGE", FRIDAY);
        List<MatchableLine> statements = List.of(acme, zenith);
        List<MatchableLine> ledger = List.of(zenithEntry, acmeEntry);

        TierOutcome outcome = PairResolver.resolve(statements, ledger,
                FuzzyTier.candidates(statements, ledger, DEFAULTS), DEFAULTS);

        assertEquals(2, outcome.matches().size());
        assertEquals(List.of(acme), outcome.matches().get(0).statementSide());
        assertEquals(List.of(acmeEntry), outcome.matches().get(0).ledgerSide());
        assertEquals(List.of(zenith), outcome.matches().get(1).statementSide());
        assertEquals(List.of(zenithEntry), outcome.matches().get(1).ledgerSide());
    }

    @Test
    void twoEntriesThatLookIdenticalToOneStatementAreAnAmbiguousTie() {
        MatchableLine statement = statement(0, "INV1", ACME, PAYMENT, FRIDAY);
        MatchableLine first = entry(0, "PAY1", ACME, PAYMENT, FRIDAY);
        MatchableLine second = entry(1, "PAY2", ACME, PAYMENT, FRIDAY);
        List<MatchableLine> statements = List.of(statement);
        List<MatchableLine> ledger = List.of(first, second);

        TierOutcome outcome = PairResolver.resolve(statements, ledger,
                FuzzyTier.candidates(statements, ledger, DEFAULTS), DEFAULTS);

        assertEquals(List.of(), outcome.matches());
        assertEquals(List.of(first, second), outcome.ambiguous().get(0).ledgerSide());
    }
}