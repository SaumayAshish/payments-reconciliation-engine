package com.saumay.payments.domain.cases;

import com.saumay.payments.domain.model.BusinessCalendar;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * SLA lengths by priority and the at-risk threshold (Q-10, ADR-0008). All arithmetic is in whole business days and
 * integer percentages.
 *
 * <p>A case is {@code AT_RISK} once {@code atRiskPercent} of its SLA has been consumed, and {@code BREACHED} once
 * the as-of business date is after its due date; on the due date itself it is still only at risk.
 */
public record SlaPolicy(Map<CasePriority, Integer> businessDaysByPriority, int atRiskPercent) {

    private static final int STANDARD_AT_RISK_PERCENT = 75;

    public SlaPolicy {
        Objects.requireNonNull(businessDaysByPriority, "businessDaysByPriority");
        EnumMap<CasePriority, Integer> copy = new EnumMap<>(CasePriority.class);
        for (CasePriority priority : CasePriority.values()) {
            Integer days = businessDaysByPriority.get(priority);
            if (days == null) {
                throw new IllegalArgumentException("missing SLA length for " + priority);
            }
            if (days < 1) {
                throw new IllegalArgumentException("SLA length must be at least one business day for " + priority);
            }
            copy.put(priority, days);
        }
        if (atRiskPercent < 1 || atRiskPercent > 100) {
            throw new IllegalArgumentException("atRiskPercent must be between 1 and 100: " + atRiskPercent);
        }
        businessDaysByPriority = Collections.unmodifiableMap(copy);
    }

    /** P1 = 1, P2 = 2, P3 = 5, P4 = 10 business days; at risk at 75 percent (Q-10). */
    public static SlaPolicy standard() {
        EnumMap<CasePriority, Integer> days = new EnumMap<>(CasePriority.class);
        days.put(CasePriority.P1, 1);
        days.put(CasePriority.P2, 2);
        days.put(CasePriority.P3, 5);
        days.put(CasePriority.P4, 10);
        return new SlaPolicy(days, STANDARD_AT_RISK_PERCENT);
    }

    public int businessDaysFor(CasePriority priority) {
        return businessDaysByPriority.get(priority);
    }

    public LocalDate dueDate(LocalDate createdBusinessDate, CasePriority priority) {
        return BusinessCalendar.addBusinessDays(createdBusinessDate, businessDaysFor(priority));
    }

    /** Whole business days from creation to the as-of date; zero if the as-of date is earlier. */
    public static int ageInBusinessDays(LocalDate createdBusinessDate, LocalDate asOf) {
        LocalDate start = BusinessCalendar.rollForward(createdBusinessDate);
        LocalDate asOfDay = BusinessCalendar.rollForward(asOf);
        return asOfDay.isBefore(start) ? 0 : BusinessCalendar.businessDaysBetween(start, asOfDay);
    }

    /**
     * The status of a case against the SLA length stored on it, not the current policy, so a later policy change
     * never re-grades existing cases.
     */
    public SlaStatus status(LocalDate createdBusinessDate, int slaBusinessDays, LocalDate asOf) {
        if (slaBusinessDays < 1) {
            throw new IllegalArgumentException("slaBusinessDays must be at least 1: " + slaBusinessDays);
        }
        int age = ageInBusinessDays(createdBusinessDate, asOf);
        if (age > slaBusinessDays) {
            return SlaStatus.BREACHED;
        }
        if ((long) age * 100 >= (long) atRiskPercent * slaBusinessDays) {
            return SlaStatus.AT_RISK;
        }
        return SlaStatus.WITHIN_SLA;
    }
}