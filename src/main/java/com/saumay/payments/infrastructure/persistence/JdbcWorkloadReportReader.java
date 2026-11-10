package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.WorkloadReportReader;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.CaseTeam;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads unresolved workload aggregates from PostgreSQL. */
public final class JdbcWorkloadReportReader implements WorkloadReportReader {

    private static final String WORKLOAD =
            "SELECT team, status, count(*) AS case_count FROM exception_case"
                    + " WHERE status <> 'RESOLVED' GROUP BY team, status ORDER BY team, status";

    private final JdbcTemplate jdbc;

    public JdbcWorkloadReportReader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public List<Group> currentWorkload() {
        return jdbc.query(WORKLOAD, (rs, rowNum) -> new Group(
                CaseTeam.valueOf(rs.getString("team")),
                CaseStatus.valueOf(rs.getString("status")),
                rs.getLong("case_count")));
    }
}