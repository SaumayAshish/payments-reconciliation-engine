package com.saumay.payments.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BusinessCalendarAddDaysTest {

    // 2026-10-09 is a Friday.
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);
    private static final LocalDate SATURDAY = FRIDAY.plusDays(1);
    private static final LocalDate MONDAY = FRIDAY.plusDays(3);

    @Test
    void zeroDaysKeepsABusinessDayAndRollsAWeekendForward() {
        assertEquals(FRIDAY, BusinessCalendar.addBusinessDays(FRIDAY, 0));
        assertEquals(MONDAY, BusinessCalendar.addBusinessDays(SATURDAY, 0));
    }

    @Test
    void weekendsAreSkipped() {
        assertEquals(MONDAY, BusinessCalendar.addBusinessDays(FRIDAY, 1));
        assertEquals(FRIDAY.plusDays(7), BusinessCalendar.addBusinessDays(FRIDAY, 5));
        assertEquals(MONDAY.plusDays(4), BusinessCalendar.addBusinessDays(MONDAY, 4));
        assertEquals(MONDAY.plusDays(1), BusinessCalendar.addBusinessDays(SATURDAY, 1));
    }

    @Test
    void aNegativeDistanceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> BusinessCalendar.addBusinessDays(FRIDAY, -1));
    }

    @Test
    void additionIsTheExactInverseOfBusinessDaysBetween() {
        LocalDate base = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 550; i++) {
            LocalDate start = base.plusDays(i);
            for (int days = 0; days <= 30; days++) {
                LocalDate result = BusinessCalendar.addBusinessDays(start, days);
                assertTrue(BusinessCalendar.isBusinessDay(result), start + " + " + days);
                assertEquals(days, BusinessCalendar.businessDaysBetween(start, result), start + " + " + days);
            }
        }
    }
}