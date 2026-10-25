package com.saumay.payments.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.application.CaseIntakeStore.IntakeItem;
import com.saumay.payments.application.CaseIntakeStore.IntakeRun;
import com.saumay.payments.application.CaseIntakeStore.NewCase;
import com.saumay.payments.domain.cases.CaseClass;
import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CasePriority;
import com.saumay.payments.domain.cases.CaseTeam;
import com.saumay.payments.domain.cases.RoutingConfig;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CaseIntakeServiceTest {

    // 2026-10-09 is a Friday.
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);
    private static final long LIMIT = RoutingConfig.STANDARD_DEFAULT_HIGH_VALUE_MINOR;

    private final FakeStore store = new FakeStore();
    private final CaseIntakeService service = new CaseIntakeService(store, RoutingConfig.defaults());

    @Test
    void everyExceptionOfACompletedRunBecomesARoutedCase() {
        store.run = new IntakeRun(7, "COMPLETED", FRIDAY, List.of(
                new IntakeItem(1, "MISSING_LEDGER", "MISSING_LEDGER:a", 12_500, "GBP"),
                new IntakeItem(2, "CURRENCY_MISMATCH", "CURRENCY_MISMATCH:b", 5_000, "EUR"),
                new IntakeItem(3, "LOW_CONFIDENCE", "LOW_CONFIDENCE:c", 9_900, "GBP"),
                new IntakeItem(4, "MISSING_STATEMENT", "MISSING_STATEMENT:d", LIMIT + 1, "GBP")));

        IntakeResult result = service.openCases(7);

        assertEquals(new IntakeResult(7, 4, 4, 0), result);
        NewCase ledger = store.byKey("MISSING_LEDGER:a");
        assertEquals(CaseTeam.LEDGER_OPERATIONS, ledger.routing().team());
        assertEquals(CaseClass.BREAK, ledger.caseClass());
        assertEquals(CaseTeam.FX_TEAM, store.byKey("CURRENCY_MISMATCH:b").routing().team());
        assertEquals(CaseClass.REVIEW, store.byKey("LOW_CONFIDENCE:c").caseClass());
        NewCase highValue = store.byKey("MISSING_STATEMENT:d");
        assertEquals(CaseTeam.SENIOR_ANALYST, highValue.routing().team());
        assertEquals(CasePriority.P1, highValue.routing().priority());
    }

    @Test
    void theCaseAgeStartsAtTheRunBusinessDateAndTheDueDateIsFixedFromIt() {
        store.run = new IntakeRun(7, "COMPLETED", FRIDAY, List.of(
                new IntakeItem(1, "MISSING_LEDGER", "MISSING_LEDGER:a", 12_500, "GBP")));
        service.openCases(7);
        NewCase created = store.byKey("MISSING_LEDGER:a");
        assertEquals(FRIDAY, created.createdBusinessDate());
        assertEquals(5, created.slaBusinessDays());
        assertEquals(LocalDate.of(2026, 10, 16), created.dueBusinessDate());
    }

    @Test
    void openingCasesTwiceForTheSameRunCreatesNothingNew() {
        store.run = new IntakeRun(7, "COMPLETED", FRIDAY, List.of(
                new IntakeItem(1, "MISSING_LEDGER", "MISSING_LEDGER:a", 12_500, "GBP"),
                new IntakeItem(2, "AMOUNT_MISMATCH", "AMOUNT_MISMATCH:b", 700, "GBP")));
        assertEquals(new IntakeResult(7, 2, 2, 0), service.openCases(7));
        assertEquals(new IntakeResult(7, 2, 0, 2), service.openCases(7));
        assertEquals(2, store.stored.size());
    }

    @Test
    void aBreakThatReturnsInALaterRunKeepsItsOriginalCase() {
        store.run = new IntakeRun(7, "COMPLETED", FRIDAY, List.of(
                new IntakeItem(1, "MISSING_LEDGER", "MISSING_LEDGER:a", 12_500, "GBP")));
        service.openCases(7);
        store.run = new IntakeRun(8, "COMPLETED", FRIDAY.plusDays(3), List.of(
                new IntakeItem(1, "MISSING_LEDGER", "MISSING_LEDGER:a", 12_500, "GBP"),
                new IntakeItem(2, "MISSING_LEDGER", "MISSING_LEDGER:z", 3_000, "GBP")));
        IntakeResult second = service.openCases(8);
        assertEquals(1, second.created());
        assertEquals(1, second.alreadyOpen());
        assertEquals(7, store.byKey("MISSING_LEDGER:a").runId());
        assertEquals(FRIDAY, store.byKey("MISSING_LEDGER:a").createdBusinessDate());
    }

    @Test
    void aRunWithNoExceptionsOpensNothing() {
        store.run = new IntakeRun(7, "COMPLETED", FRIDAY, List.of());
        assertEquals(new IntakeResult(7, 0, 0, 0), service.openCases(7));
        assertTrue(store.stored.isEmpty());
    }

    @Test
    void anUnknownRunIsRejected() {
        assertThrows(RunRejectedException.class, () -> service.openCases(99));
    }

    @Test
    void aRunThatHasNotCompletedIsRejectedAndOpensNothing() {
        for (String status : List.of("CREATED", "RUNNING", "FAILED")) {
            store.run = new IntakeRun(7, status, FRIDAY, List.of(
                    new IntakeItem(1, "MISSING_LEDGER", "MISSING_LEDGER:a", 12_500, "GBP")));
            assertThrows(RunRejectedException.class, () -> service.openCases(7), status);
        }
        assertTrue(store.stored.isEmpty());
    }

    /** In-memory stand-in that enforces the same one-case-per-natural-key rule as the database index. */
    private static final class FakeStore implements CaseIntakeStore {
        IntakeRun run;
        final List<NewCase> stored = new ArrayList<>();
        private final Set<String> keys = new HashSet<>();

        @Override
        public Optional<IntakeRun> loadIntake(long runId) {
            return run != null && run.runId() == runId ? Optional.of(run) : Optional.empty();
        }

        @Override
        public int insert(List<NewCase> cases) {
            int created = 0;
            for (NewCase c : cases) {
                if (keys.add(c.naturalKey())) {
                    stored.add(c);
                    created++;
                }
            }
            return created;
        }

        NewCase byKey(String key) {
            return stored.stream().filter(c -> c.naturalKey().equals(key)).findFirst().orElseThrow();
        }
    }
}