package com.saumay.payments.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.saumay.payments.application.ResolutionReportReader.ResolvedCase;
import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CaseTeam;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResolutionReportServiceTest {

    private static final Instant START = Instant.parse("2026-11-10T10:00:00Z");

    @Test
    void aggregatesElapsedSecondsAndReportsInvalidTimeOrder() {
        var validOne = new ResolvedCase(CaseTeam.RECON_ANALYSTS, CaseKind.MISSING_LEDGER,
                START, START.plusSeconds(10));
        var validTwo = new ResolvedCase(CaseTeam.RECON_ANALYSTS, CaseKind.MISSING_LEDGER,
                START, START.plusSeconds(30));
        var invalid = new ResolvedCase(CaseTeam.RECON_ANALYSTS, CaseKind.MISSING_LEDGER,
                START, START.minusSeconds(1));

        var report = new ResolutionReportService(() -> List.of(validOne, invalid, validTwo)).report();
        var group = report.groups().get(0);

        assertEquals(3, report.resolvedCount());
        assertEquals(3, group.resolvedCount());
        assertEquals(2, group.measuredCount());
        assertEquals(1, group.invalidTimeOrderCount());
        assertEquals(40, group.totalSeconds());
        assertEquals(10L, group.minimumSeconds());
        assertEquals(30L, group.maximumSeconds());
    }

    @Test
    void groupsAreDeterministicAndEmptyGroupsHaveNoMinimum() {
        var first = new ResolvedCase(CaseTeam.RECON_ANALYSTS, CaseKind.MISSING_LEDGER,
                START, START.minusSeconds(1));
        var second = new ResolvedCase(CaseTeam.BANK_OPERATIONS, CaseKind.AMOUNT_MISMATCH,
                START, START.plusSeconds(5));

        var forward = new ResolutionReportService(() -> List.of(first, second)).report();
        var reverse = new ResolutionReportService(() -> List.of(second, first)).report();

        assertEquals(forward, reverse);
        assertEquals(CaseTeam.BANK_OPERATIONS, forward.groups().get(0).team());
        assertEquals(null, forward.groups().get(1).minimumSeconds());
        assertEquals(null, forward.groups().get(1).maximumSeconds());
    }
}