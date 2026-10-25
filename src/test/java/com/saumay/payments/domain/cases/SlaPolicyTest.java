package com.saumay.payments.domain.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlaPolicyTest {

    // 2026-10-09 is a Friday.
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    private final SlaPolicy policy = SlaPolicy.standard();

    @Test
    void theStandardPolicyIsQ10() {
        assertEquals(1, policy.businessDaysFor(CasePriority.P1));
        assertEquals(2, policy.businessDaysFor(CasePriority.P2));
        assertEquals(5, policy.businessDaysFor(CasePriority.P3));
        assertEquals(10, policy.businessDaysFor(CasePriority.P4));
        assertEquals(75, policy.atRiskPercent());
    }

    @Test
    void dueDatesSkipWeekends() {
        assertEquals(LocalDate.of(2026, 10, 12), policy.dueDate(FRIDAY, CasePriority.P1));
        assertEquals(LocalDate.of(2026, 10, 13), policy.dueDate(FRIDAY, CasePriority.P2));
        assertEquals(LocalDate.of(2026, 10, 16), policy.dueDate(FRIDAY, CasePriority.P3));
        assertEquals(LocalDate.of(2026, 10, 23), policy.dueDate(FRIDAY, CasePriority.P4));
    }

    @Test
    void aP3CaseMovesFromWithinToAtRiskToBreached() {
        // Created Monday 12 October with 5 business days: due Monday 19 October.
        assertEquals(SlaStatus.WITHIN_SLA, policy.status(MONDAY, 5, MONDAY));
        assertEquals(SlaStatus.WITHIN_SLA, policy.status(MONDAY, 5, LocalDate.of(2026, 10, 15)));
        assertEquals(SlaStatus.AT_RISK, policy.status(MONDAY, 5, LocalDate.of(2026, 10, 16)));
        assertEquals(SlaStatus.AT_RISK, policy.status(MONDAY, 5, LocalDate.of(2026, 10, 19)));
        assertEquals(SlaStatus.BREACHED, policy.status(MONDAY, 5, LocalDate.of(2026, 10, 20)));
    }

    @Test
    void aCaseIsNotBreachedOnItsDueDate() {
        LocalDate due = policy.dueDate(MONDAY, CasePriority.P3);
        assertEquals(SlaStatus.AT_RISK, policy.status(MONDAY, 5, due));
        assertEquals(SlaStatus.BREACHED, policy.status(MONDAY, 5, due.plusDays(1)));
    }

    @Test
    void weekendAsOfDatesCountAsTheFollowingMonday() {
        // P1 created Friday: Monday is one business day old (at risk), Tuesday is breached.
        assertEquals(SlaStatus.AT_RISK, policy.status(FRIDAY, 1, FRIDAY.plusDays(1)));
        assertEquals(SlaStatus.AT_RISK, policy.status(FRIDAY, 1, FRIDAY.plusDays(2)));
        assertEquals(SlaStatus.BREACHED, policy.status(FRIDAY, 1, MONDAY.plusDays(1)));
    }

    @Test
    void anAsOfDateBeforeCreationIsWithinSla() {
        assertEquals(SlaStatus.WITHIN_SLA, policy.status(MONDAY, 5, FRIDAY));
        assertEquals(0, SlaPolicy.ageInBusinessDays(MONDAY, FRIDAY));
    }

    @Test
    void theAtRiskPercentageIsConfigurable() {
        Map<CasePriority, Integer> days = new EnumMap<>(CasePriority.class);
        for (CasePriority priority : CasePriority.values()) {
            days.put(priority, 10);
        }
        SlaPolicy halfway = new SlaPolicy(days, 50);
        assertEquals(SlaStatus.WITHIN_SLA, halfway.status(MONDAY, 10, MONDAY.plusDays(3)));
        assertEquals(SlaStatus.AT_RISK, halfway.status(MONDAY, 10, MONDAY.plusDays(7)));
    }

    @Test
    void ageIsCountedInBusinessDays() {
        assertEquals(1, SlaPolicy.ageInBusinessDays(FRIDAY, MONDAY));
        assertEquals(0, SlaPolicy.ageInBusinessDays(FRIDAY, FRIDAY));
        assertEquals(5, SlaPolicy.ageInBusinessDays(FRIDAY, FRIDAY.plusDays(7)));
    }

    @Test
    void invalidPoliciesAreRefused() {
        Map<CasePriority, Integer> missing = new EnumMap<>(CasePriority.class);
        missing.put(CasePriority.P1, 1);
        assertThrows(IllegalArgumentException.class, () -> new SlaPolicy(missing, 75));

        Map<CasePriority, Integer> zero = new EnumMap<>(CasePriority.class);
        for (CasePriority priority : CasePriority.values()) {
            zero.put(priority, 0);
        }
        assertThrows(IllegalArgumentException.class, () -> new SlaPolicy(zero, 75));

        Map<CasePriority, Integer> ok = new EnumMap<>(CasePriority.class);
        for (CasePriority priority : CasePriority.values()) {
            ok.put(priority, 1);
        }
        assertThrows(IllegalArgumentException.class, () -> new SlaPolicy(ok, 0));
        assertThrows(IllegalArgumentException.class, () -> new SlaPolicy(ok, 101));
        assertThrows(IllegalArgumentException.class, () -> policy.status(MONDAY, 0, MONDAY));
    }
}