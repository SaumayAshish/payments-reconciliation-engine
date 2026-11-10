package com.saumay.payments.application;

import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CaseTeam;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Reads one current snapshot of unresolved cases for ageing reports. */
public interface AgeingReportReader {

    List<CaseRow> unresolvedCases();

    record CaseRow(CaseTeam team, CaseKind kind, LocalDate createdBusinessDate, int slaBusinessDays) {
        public CaseRow {
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(createdBusinessDate, "createdBusinessDate");
            if (slaBusinessDays < 1) {
                throw new IllegalArgumentException("slaBusinessDays must be positive");
            }
        }
    }
}