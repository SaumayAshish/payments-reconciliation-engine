package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.ResolutionReportReader;
import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CaseTeam;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads the current resolved-case snapshot for elapsed-time statistics. */
public final class JdbcResolutionReportReader implements ResolutionReportReader {

    private static final String RESOLVED =
            "SELECT team, kind, created_at, resolved_at FROM exception_case"
                    + " WHERE status = 'RESOLVED' ORDER BY id";

    private final JdbcTemplate jdbc;

    public JdbcResolutionReportReader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public List<ResolvedCase> resolvedCases() {
        return jdbc.query(RESOLVED, (rs, rowNum) -> new ResolvedCase(
                CaseTeam.valueOf(rs.getString("team")),
                CaseKind.valueOf(rs.getString("kind")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("resolved_at", OffsetDateTime.class).toInstant()));
    }
}