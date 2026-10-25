package com.saumay.payments.domain.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Business-day arithmetic: Monday to Friday are business days. Holiday calendars are out of scope for v1 (FRD
 * conventions) and would be added behind this type.
 *
 * <p>Weekend dates roll forward to the following Monday before any distance is computed (FRD EC-04.6), so a
 * Saturday and the following Monday are at distance zero.
 */
public final class BusinessCalendar {

    private BusinessCalendar() {
    }

    public static boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    /** Returns the date itself on a business day, otherwise the following Monday. */
    public static LocalDate rollForward(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case SATURDAY -> date.plusDays(2);
            case SUNDAY -> date.plusDays(1);
            default -> date;
        };
    }

    /** Absolute number of business days between two dates after rolling both forward. Symmetric. */
    public static int businessDaysBetween(LocalDate a, LocalDate b) {
        LocalDate from = rollForward(a);
        LocalDate to = rollForward(b);
        if (from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }
        long days = ChronoUnit.DAYS.between(from, to);
        long remainder = days % 7;
        long result = (days / 7) * 5 + remainder;
        // A partial week that crosses a weekend loses the two weekend days.
        if (remainder > 0 && from.getDayOfWeek().getValue() + remainder > 5) {
            result -= 2;
        }
        return Math.toIntExact(result);
    }

    /**
     * The business day that is {@code days} business days after {@code start}, after a weekend start has rolled
     * forward. For a non-negative distance it is the exact inverse of {@link #businessDaysBetween}.
     */
    public static LocalDate addBusinessDays(LocalDate start, int days) {
        if (days < 0) {
            throw new IllegalArgumentException("days must not be negative: " + days);
        }
        LocalDate date = rollForward(start);
        for (int remaining = days; remaining > 0; remaining--) {
            date = rollForward(date.plusDays(1));
        }
        return date;
    }
}