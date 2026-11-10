package com.saumay.payments.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.saumay.payments.application.AgeingReportReader.CaseRow;
import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CaseTeam;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgeingReportServiceTest {

    @Test
    void groupsByTeamAndKindAndCountsAgeAndSlaBoundaries() {
        LocalDate asOf = LocalDate.parse("2026-11-10");
        List<CaseRow> rows = List.of(
                new CaseRow(CaseTeam.RECON_ANALYSTS, CaseKind.MISSING_LEDGER,
                        LocalDate.parse("2026-11-02"), 5),
                new CaseRow(CaseTeam.RECON_ANALYSTS, CaseKind.MISSING_LEDGER,
                        LocalDate.parse("2026-11-09"), 5),
                new CaseRow(CaseTeam.BANK_OPERATIONS, CaseKind.AMOUNT_MISMATCH,
                        LocalDate.parse("2026-11-10"), 5));

        var report = new AgeingReportService(() -> rows).report(asOf);

        assertEquals(asOf, report.asOf());
        assertEquals(3, report.unresolvedCount());
        assertEquals(2, report.groups().size());
        assertEquals(CaseTeam.BANK_OPERATIONS, report.groups().get(0).team());
        var group = report.groups().get(1);
        assertEquals(CaseTeam.RECON_ANALYSTS, group.team());
        assertEquals(CaseKind.MISSING_LEDGER, group.kind());
        assertEquals(2, group.total());
        assertEquals(1, group.zeroToOne());
        assertEquals(1, group.sixToTen());
        assertEquals(1, group.withinSla());
        assertEquals(1, group.breached());
    }

    @Test
    void inputOrderDoesNotChangeTheReport() {
        LocalDate asOf = LocalDate.parse("2026-11-10");
        CaseRow a = new CaseRow(CaseTeam.RECON_ANALYSTS, CaseKind.MISSING_LEDGER,
                LocalDate.parse("2026-11-02"), 5);
        CaseRow b = new CaseRow(CaseTeam.BANK_OPERATIONS, CaseKind.AMOUNT_MISMATCH,
                LocalDate.parse("2026-11-09"), 5);

        var forward = new AgeingReportService(() -> List.of(a, b)).report(asOf);
        var reverse = new AgeingReportService(() -> List.of(b, a)).report(asOf);

        assertEquals(forward, reverse);
    }

    @Test
    void anEmptySnapshotProducesAnEmptyReport() {
        var report = new AgeingReportService(List::of).report(LocalDate.parse("2026-11-10"));

        assertEquals(0, report.unresolvedCount());
        assertEquals(List.of(), report.groups());
    }
}