package com.saumay.payments.application;

import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.CaseTeam;
import java.util.List;
import java.util.Objects;

/** Reads current unresolved workload grouped by team and status. */
public interface WorkloadReportReader {

    List<Group> currentWorkload();

    record Group(CaseTeam team, CaseStatus status, long count) {
        public Group {
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(status, "status");
            if (status == CaseStatus.RESOLVED || count < 1) {
                throw new IllegalArgumentException("workload groups must contain unresolved cases");
            }
        }
    }
}