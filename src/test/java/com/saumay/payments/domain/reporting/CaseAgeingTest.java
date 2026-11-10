package com.saumay.payments.domain.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saumay.payments.domain.cases.SlaPolicy;
import com.saumay.payments.domain.cases.SlaStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CaseAgeingTest {

    private static final LocalDate MONDAY = LocalDate.parse("2026-11-02");

    @Test
    void bucketBoundariesDoNotOverlap() {
        assertEquals(AgeingBucket.ZERO_TO_ONE, AgeingBucket.forAge(0));
        assertEquals(AgeingBucket.ZERO_TO_ONE, AgeingBucket.forAge(1));
        assertEquals(AgeingBucket.TWO_TO_FIVE, AgeingBucket.forAge(2));
        assertEquals(AgeingBucket.TWO_TO_FIVE, AgeingBucket.forAge(5));
        assertEquals(AgeingBucket.SIX_TO_TEN, AgeingBucket.forAge(6));
        assertEquals(AgeingBucket.SIX_TO_TEN, AgeingBucket.forAge(10));
        assertEquals(AgeingBucket.OVER_TEN, AgeingBucket.forAge(11));
    }

    @Test
    void aNegativeAgeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AgeingBucket.forAge(-1));
    }

    @Test
    void evaluationUsesBusinessDaysAndTheStoredSlaDuration() {
        CaseAgeing onDueDate = CaseAgeing.evaluate(
                MONDAY, 5, LocalDate.parse("2026-11-09"), SlaPolicy.standard());

        assertEquals(5, onDueDate.ageBusinessDays());
        assertEquals(AgeingBucket.TWO_TO_FIVE, onDueDate.bucket());
        assertEquals(SlaStatus.AT_RISK, onDueDate.slaStatus());

        CaseAgeing afterDueDate = CaseAgeing.evaluate(
                MONDAY, 5, LocalDate.parse("2026-11-10"), SlaPolicy.standard());

        assertEquals(6, afterDueDate.ageBusinessDays());
        assertEquals(AgeingBucket.SIX_TO_TEN, afterDueDate.bucket());
        assertEquals(SlaStatus.BREACHED, afterDueDate.slaStatus());
    }

    @Test
    void aWeekendAsOfDateUsesTheExistingCalendarRules() {
        CaseAgeing result = CaseAgeing.evaluate(
                MONDAY, 5, LocalDate.parse("2026-11-07"), SlaPolicy.standard());

        assertEquals(5, result.ageBusinessDays());
        assertEquals(SlaStatus.AT_RISK, result.slaStatus());
    }

    @Test
    void anAsOfDateBeforeCreationHasZeroAge() {
        CaseAgeing result = CaseAgeing.evaluate(
                MONDAY, 5, LocalDate.parse("2026-10-30"), SlaPolicy.standard());

        assertEquals(0, result.ageBusinessDays());
        assertEquals(AgeingBucket.ZERO_TO_ONE, result.bucket());
        assertEquals(SlaStatus.WITHIN_SLA, result.slaStatus());
    }

    @Test
    void theValueRejectsAnAgeAndBucketThatDisagree() {
        assertThrows(IllegalArgumentException.class,
                () -> new CaseAgeing(-1, AgeingBucket.ZERO_TO_ONE, SlaStatus.WITHIN_SLA));
        assertThrows(IllegalArgumentException.class,
                () -> new CaseAgeing(2, AgeingBucket.ZERO_TO_ONE, SlaStatus.WITHIN_SLA));
    }

    @Test
    void theValueRequiresItsBucketAndSlaStatus() {
        assertThrows(NullPointerException.class,
                () -> new CaseAgeing(0, null, SlaStatus.WITHIN_SLA));
        assertThrows(NullPointerException.class,
                () -> new CaseAgeing(0, AgeingBucket.ZERO_TO_ONE, null));
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> CaseAgeing.evaluate(null, 5, MONDAY, SlaPolicy.standard()));
        assertThrows(NullPointerException.class,
                () -> CaseAgeing.evaluate(MONDAY, 5, null, SlaPolicy.standard()));
        assertThrows(NullPointerException.class,
                () -> CaseAgeing.evaluate(MONDAY, 5, MONDAY, null));
        assertThrows(IllegalArgumentException.class,
                () -> CaseAgeing.evaluate(MONDAY, 0, MONDAY, SlaPolicy.standard()));
    }
}