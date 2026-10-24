package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExceptionClassifierTest {

    private static final MatchingConfig CONFIG = MatchingConfig.defaults();
    private static final LocalDate FRIDAY = Lines.FRIDAY;
    private static final LocalDate BUSINESS_DATE = FRIDAY;

    private static ExceptionClassifier.Classification classify(List<MatchableLine> statements,
                                                               List<MatchableLine> ledger) {
        return classify(statements, ledger, CONFIG);
    }

    private static ExceptionClassifier.Classification classify(List<MatchableLine> statements,
                                                               List<MatchableLine> ledger, MatchingConfig config) {
        return ExceptionClassifier.classify(MatchingPipeline.run(statements, ledger, config), BUSINESS_DATE, config);
    }

    @Test
    void anAutoConfirmedMatchRaisesNothing() {
        ExceptionClassifier.Classification result = classify(List.of(Lines.statement(0, "R1", 1000, "GBP", FRIDAY)),
                List.of(Lines.ledger(0, "R1", 1000, "GBP", FRIDAY)));

        assertEquals(List.of(), result.exceptions());
        assertEquals(List.of(), result.carriedForward());
    }

    @Test
    void aReviewBandMatchIsLowConfidenceAndCarriesTheProposedMatch() {
        // Wednesday is 3 business days after Friday: the edge of the window, score 0.900, review band.
        MatchableLine statement = Lines.statement(0, "R1", 1000, "GBP", FRIDAY);
        MatchableLine entry = Lines.ledger(0, "R1", 1000, "GBP", FRIDAY.plusDays(5));

        RunException exception = classify(List.of(statement), List.of(entry)).exceptions().get(0);

        assertEquals(ExceptionKind.LOW_CONFIDENCE, exception.kind());
        assertEquals(List.of(statement, entry), exception.subjects());
        assertEquals("tier=TOLERANCE score=0.900 band=REVIEW", exception.detail());
        assertTrue(exception.reason().startsWith("TOLERANCE"));
        assertEquals(List.of(), exception.candidates());
    }

    @Test
    void aMatchBelowAReconfiguredReviewThresholdIsStillLowConfidence() {
        MatchingConfig strict = CONFIG.withThresholds(1000, 980);
        MatchableLine statement = Lines.statement(0, "R1", 1000, "GBP", FRIDAY);
        MatchableLine entry = Lines.ledger(0, "R1", 1000, "GBP", FRIDAY.plusDays(5));

        RunException exception = classify(List.of(statement), List.of(entry), strict).exceptions().get(0);

        assertEquals(ExceptionKind.LOW_CONFIDENCE, exception.kind());
        assertEquals("tier=TOLERANCE score=0.900 band=EXCEPTION", exception.detail());
    }

    @Test
    void anAmbiguousGroupIsATieWithAtMostThreeCandidatesInCanonicalOrder() {
        MatchableLine statement = Lines.statement(0, "S1", 1000, "GBP", FRIDAY);
        List<MatchableLine> ledger = List.of(Lines.ledger(3, "L4", 500, "GBP", FRIDAY),
                Lines.ledger(0, "L1", 600, "GBP", FRIDAY), Lines.ledger(2, "L3", 500, "GBP", FRIDAY),
                Lines.ledger(1, "L2", 400, "GBP", FRIDAY));

        RunException exception = classify(List.of(statement), ledger).exceptions().get(0);

        assertEquals(ExceptionKind.AMBIGUOUS_TIE, exception.kind());
        assertEquals(AmbiguousGroup.REASON, exception.reason());
        assertEquals(5, exception.subjects().size());
        assertEquals(statement, exception.subjects().get(0));
        assertEquals(List.of(ledger.get(1), ledger.get(3), ledger.get(2)), exception.candidates());
    }

    @Test
    void anUnmatchedStatementLineWithNothingSimilarIsMissingLedger() {
        MatchableLine statement = Lines.statement(0, "R1", 1000, "GBP", FRIDAY);

        RunException exception = classify(List.of(statement),
                List.of(Lines.ledger(0, "OTHER", 777, "GBP", FRIDAY))).exceptions().get(0);

        assertEquals(ExceptionKind.MISSING_LEDGER, exception.kind());
        assertEquals(List.of(statement), exception.subjects());
        assertEquals(List.of(), exception.candidates());
    }

    @Test
    void sameReferenceAccountCurrencyAndDateButDifferentAmountIsAnAmountMismatch() {
        MatchableLine statement = Lines.statement(0, "R1", 1000, "GBP", FRIDAY);
        MatchableLine entry = Lines.ledger(0, "R1", 1001, "GBP", FRIDAY);

        RunException exception = classify(List.of(statement), List.of(entry)).exceptions().get(0);

        assertEquals(ExceptionKind.AMOUNT_MISMATCH, exception.kind());
        assertEquals("deltaMinor=1", exception.detail());
        assertEquals(List.of(entry), exception.candidates());
    }

    @Test
    void anAmountMismatchOnADifferentDateIsOnlyMissingLedger() {
        RunException exception = classify(List.of(Lines.statement(0, "R1", 1000, "GBP", FRIDAY)),
                List.of(Lines.ledger(0, "R1", 1001, "GBP", FRIDAY.plusDays(3)))).exceptions().get(0);

        assertEquals(ExceptionKind.MISSING_LEDGER, exception.kind());
    }

    @Test
    void sameReferenceAndAmountInAnotherCurrencyIsACurrencyMismatch() {
        MatchableLine statement = Lines.statement(0, "R1", 1000, "GBP", FRIDAY);
        MatchableLine entry = Lines.ledger(0, "R1", 1000, "USD", FRIDAY);

        RunException exception = classify(List.of(statement), List.of(entry)).exceptions().get(0);

        assertEquals(ExceptionKind.CURRENCY_MISMATCH, exception.kind());
        assertEquals("statementCurrency=GBP ledgerCurrency=USD", exception.detail());
        assertEquals(List.of(entry), exception.candidates());
    }

    @Test
    void aCurrencyMismatchOutsideTheDateWindowIsOnlyMissingLedger() {
        RunException exception = classify(List.of(Lines.statement(0, "R1", 1000, "GBP", FRIDAY)),
                List.of(Lines.ledger(0, "R1", 1000, "USD", FRIDAY.plusDays(14)))).exceptions().get(0);

        assertEquals(ExceptionKind.MISSING_LEDGER, exception.kind());
    }

    @Test
    void mismatchCandidatesAreCappedAtThreeInCanonicalOrder() {
        MatchableLine statement = Lines.statement(0, "R1", 1000, "GBP", FRIDAY);
        List<MatchableLine> ledger = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ledger.add(Lines.ledger(i, "R1", 1001 + i, "GBP", FRIDAY));
        }

        RunException exception = classify(List.of(statement), ledger).exceptions().get(0);

        assertEquals(ExceptionKind.AMOUNT_MISMATCH, exception.kind());
        assertEquals(ledger.subList(0, 3), exception.candidates());
    }

    @Test
    void anUnmatchedLedgerEntryRaisesMissingStatementOnlyOnceItIsOlderThanTheLookbackWindow() {
        // Lookback is 10 business days; FRIDAY minus 14 calendar days is exactly 10, minus 15 is 11.
        MatchableLine atBoundary = Lines.ledger(0, "A", 111, "GBP", FRIDAY.minusDays(14));
        MatchableLine tooOld = Lines.ledger(1, "B", 222, "GBP", FRIDAY.minusDays(15));
        MatchableLine recent = Lines.ledger(2, "C", 333, "GBP", FRIDAY.minusDays(1));
        MatchableLine future = Lines.ledger(3, "D", 444, "GBP", FRIDAY.plusDays(5));

        ExceptionClassifier.Classification result = classify(List.of(), List.of(future, recent, tooOld, atBoundary));

        assertEquals(1, result.exceptions().size());
        assertEquals(ExceptionKind.MISSING_STATEMENT, result.exceptions().get(0).kind());
        assertEquals(List.of(tooOld), result.exceptions().get(0).subjects());
        assertEquals(List.of(atBoundary, recent, future), result.carriedForward());
    }

    @Test
    void exceptionsAreSortedByKindThenFirstSubjectWhateverTheInputOrder() {
        List<MatchableLine> statements = new ArrayList<>();
        List<MatchableLine> ledger = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            statements.add(Lines.statement(i, "S" + i, 1000 + 10 * i, "GBP", FRIDAY));
            ledger.add(Lines.ledger(i, "L" + i, 5000 + 10 * i, "GBP", FRIDAY.minusDays(20)));
        }
        ExceptionClassifier.Classification expected = classify(statements, ledger);
        Collections.reverse(statements);
        Collections.reverse(ledger);

        assertEquals(expected, classify(statements, ledger));
        List<String> kinds = new ArrayList<>();
        for (RunException exception : expected.exceptions()) {
            kinds.add(exception.kind().name());
        }
        List<String> sorted = new ArrayList<>(kinds);
        Collections.sort(sorted);
        assertEquals(sorted, kinds);
        assertTrue(kinds.contains("MISSING_LEDGER") && kinds.contains("MISSING_STATEMENT"));
    }

    @Test
    void theNaturalKeyIsTheKindAndTheFirstSubjectLine() {
        MatchableLine statement = Lines.statement(0, "R1", 1000, "GBP", FRIDAY);
        RunException exception = classify(List.of(statement), List.of()).exceptions().get(0);

        assertEquals("MISSING_LEDGER:" + statement.key().value(), exception.naturalKey());
    }
}