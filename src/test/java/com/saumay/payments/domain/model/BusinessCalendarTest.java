package com.saumay.payments.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {

    // 2026-10-09 is a Friday.
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);
    private static final LocalDate SATURDAY = FRIDAY.plusDays(1);
    private static final LocalDate SUNDAY = FRIDAY.plusDays(2);
    private static final LocalDate MONDAY = FRIDAY.plusDays(3);

    @Test
    void weekendDatesRollForwardToMonday() {
        assertEquals(MONDAY, BusinessCalendar.rollForward(SATURDAY));
        assertEquals(MONDAY, BusinessCalendar.rollForward(SUNDAY));
        assertEquals(FRIDAY, BusinessCalendar.rollForward(FRIDAY));
        assertEquals(MONDAY, BusinessCalendar.rollForward(MONDAY));
    }

    @Test
    void weekendsAreNotBusinessDays() {
        assertFalse(BusinessCalendar.isBusinessDay(SATURDAY));
        assertFalse(BusinessCalendar.isBusinessDay(SUNDAY));
        assertTrue(BusinessCalendar.isBusinessDay(FRIDAY));
    }

    @Test
    void fridayToMondayIsOneBusinessDay() {
        assertEquals(1, BusinessCalendar.businessDaysBetween(FRIDAY, MONDAY));
    }

    @Test
    void aSaturdayAndTheFollowingMondayAreAtDistanceZero() {
        assertEquals(0, BusinessCalendar.businessDaysBetween(SATURDAY, MONDAY));
        assertEquals(0, BusinessCalendar.businessDaysBetween(SATURDAY, SUNDAY));
    }

    @Test
    void sameDayAndFullWeekDistances() {
        assertEquals(0, BusinessCalendar.businessDaysBetween(FRIDAY, FRIDAY));
        assertEquals(5, BusinessCalendar.businessDaysBetween(FRIDAY, FRIDAY.plusDays(7)));
        assertEquals(4, BusinessCalendar.businessDaysBetween(MONDAY, MONDAY.plusDays(4)));
    }

    @Test
    void closedFormMatchesDayByDayCountingForEveryPairInAYearAndAHalf() {
        LocalDate base = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 550; i++) {
            for (int j = 0; j < 550; j++) {
                LocalDate x = base.plusDays(i);
                LocalDate y = base.plusDays(j);
                int expected = countDayByDay(x, y);
                assertEquals(expected, BusinessCalendar.businessDaysBetween(x, y), x + " to " + y);
                assertEquals(expected, BusinessCalendar.businessDaysBetween(y, x), "symmetry " + x + " " + y);
            }
        }
    }

    private static int countDayByDay(LocalDate x, LocalDate y) {
        LocalDate from = BusinessCalendar.rollForward(x);
        LocalDate to = BusinessCalendar.rollForward(y);
        if (from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }
        int count = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (BusinessCalendar.isBusinessDay(d)) {
                count++;
            }
        }
        return count;
    }
}