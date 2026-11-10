package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.MatchReportReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads completed-run match statistics from PostgreSQL. */
public final class JdbcMatchReportReader implements MatchReportReader {

    private static final String REPORT =
            "SELECT r.id, r.business_date, r.statement_line_count, r.ledger_entry_count,"
                    + " r.match_count, r.auto_match_count, r.review_match_count, r.exception_count,"
                    + " m.tier, m.band, count(m.ordinal) AS band_count"
                    + " FROM reconciliation_run r"
                    + " LEFT JOIN run_match m ON m.run_id = r.id"
                    + " WHERE r.id = ? AND r.status = 'COMPLETED'"
                    + " GROUP BY r.id, m.tier, m.band"
                    + " ORDER BY m.tier, m.band";

    private final JdbcTemplate jdbc;

    public JdbcMatchReportReader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<Report> completedRun(long runId) {
        List<Row> rows = jdbc.query(REPORT, (rs, rowNum) -> new Row(
                rs.getLong("id"), rs.getObject("business_date", LocalDate.class),
                rs.getInt("statement_line_count"), rs.getInt("ledger_entry_count"),
                rs.getInt("match_count"), rs.getInt("auto_match_count"),
                rs.getInt("review_match_count"), rs.getInt("exception_count"),
                rs.getString("tier"), rs.getString("band"), rs.getLong("band_count")), runId);

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row first = rows.get(0);
        List<Band> bands = new ArrayList<>();
        for (Row row : rows) {
            if (row.tier() != null) {
                bands.add(new Band(row.tier(), row.band(), row.bandCount()));
            }
        }
        return Optional.of(new Report(first.id(), first.businessDate(), first.statementLineCount(),
                first.ledgerEntryCount(), first.matchCount(), first.autoMatchCount(),
                first.reviewMatchCount(), first.exceptionCount(), bands));
    }

    private record Row(long id, LocalDate businessDate, int statementLineCount, int ledgerEntryCount,
            int matchCount, int autoMatchCount, int reviewMatchCount, int exceptionCount,
            String tier, String band, long bandCount) {
    }
}