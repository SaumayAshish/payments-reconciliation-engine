package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.OperationalMetricsReader;
import com.saumay.payments.domain.cases.CaseKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads low-cardinality telemetry aggregates from PostgreSQL. */
public final class JdbcOperationalMetricsReader implements OperationalMetricsReader {

    private static final String LATEST =
            "SELECT r.statement_line_count,"
                    + " count(DISTINCT l.statement_seq) FILTER (WHERE m.band = 'AUTO_CONFIRM') AS auto_lines,"
                    + " count(DISTINCT m.ordinal) FILTER (WHERE m.tier = 'EXACT') AS exact_count,"
                    + " count(DISTINCT m.ordinal) FILTER (WHERE m.tier = 'TOLERANCE') AS tolerance_count,"
                    + " count(DISTINCT m.ordinal) FILTER (WHERE m.tier = 'FUZZY') AS fuzzy_count,"
                    + " count(DISTINCT m.ordinal) FILTER (WHERE m.tier = 'ONE_TO_MANY') AS one_to_many_count"
                    + " FROM reconciliation_run r"
                    + " LEFT JOIN run_match m ON m.run_id = r.id"
                    + " LEFT JOIN run_match_line l ON l.run_id = m.run_id AND l.match_ordinal = m.ordinal"
                    + " WHERE r.id = (SELECT max(id) FROM reconciliation_run WHERE status = 'COMPLETED')"
                    + " GROUP BY r.id";

    private static final String EXCEPTIONS =
            "SELECT kind, count(*) AS case_count FROM exception_case"
                    + " WHERE status <> 'RESOLVED' GROUP BY kind ORDER BY kind";

    private final JdbcTemplate jdbc;

    public JdbcOperationalMetricsReader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<RunSnapshot> latestCompletedRun() {
        List<RunSnapshot> rows = jdbc.query(LATEST, (rs, rowNum) -> new RunSnapshot(
                rs.getLong("statement_line_count"), rs.getLong("auto_lines"),
                rs.getLong("exact_count"), rs.getLong("tolerance_count"),
                rs.getLong("fuzzy_count"), rs.getLong("one_to_many_count")));
        return rows.stream().findFirst();
    }

    @Override
    public Map<CaseKind, Long> unresolvedByKind() {
        Map<CaseKind, Long> result = new EnumMap<>(CaseKind.class);
        jdbc.query(EXCEPTIONS, rs -> {
            result.put(CaseKind.valueOf(rs.getString("kind")), rs.getLong("case_count"));
        });
        return Map.copyOf(result);
    }
}