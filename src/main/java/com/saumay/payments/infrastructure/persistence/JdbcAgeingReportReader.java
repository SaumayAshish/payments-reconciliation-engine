package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.AgeingReportReader;
import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CaseTeam;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads the current unresolved case snapshot for reporting. */
public final class JdbcAgeingReportReader implements AgeingReportReader {

    private static final String UNRESOLVED =
            "SELECT team, kind, created_business_date, sla_business_days"
                    + " FROM exception_case WHERE status <> 'RESOLVED' ORDER BY id";

    private final JdbcTemplate jdbc;

    public JdbcAgeingReportReader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public List<CaseRow> unresolvedCases() {
        return jdbc.query(UNRESOLVED, (rs, rowNum) -> new CaseRow(
                CaseTeam.valueOf(rs.getString("team")),
                CaseKind.valueOf(rs.getString("kind")),
                rs.getObject("created_business_date", LocalDate.class),
                rs.getInt("sla_business_days")));
    }
}