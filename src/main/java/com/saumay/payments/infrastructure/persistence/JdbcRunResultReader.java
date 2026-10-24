package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.RunResultReader;
import com.saumay.payments.domain.model.LineSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL adapter for {@link RunResultReader}. One query per page: the page of parent rows is chosen in a
 * sub-select (ordered, limited), and the lines are joined to it, so the page size counts matches or exceptions,
 * never lines.
 */
public final class JdbcRunResultReader implements RunResultReader {

    private static final String LINE_JOINS =
            " LEFT JOIN statement_line s ON s.seq = l.statement_seq"
                    + " LEFT JOIN ledger_entry e ON e.seq = l.ledger_seq";
    private static final String LINE_COLUMNS =
            "l.statement_seq, l.ledger_seq,"
                    + " COALESCE(s.reference, e.reference) AS reference,"
                    + " COALESCE(s.amount_minor, e.amount_minor) AS amount_minor,"
                    + " COALESCE(s.currency, e.currency) AS currency,"
                    + " COALESCE(s.value_date, e.value_date) AS value_date";

    private static final String SELECT_MATCHES =
            "SELECT m.ordinal, m.tier, m.score_milli, m.band, m.reason, " + LINE_COLUMNS
                    + " FROM run_match m"
                    + " JOIN run_match_line l ON l.run_id = m.run_id AND l.match_ordinal = m.ordinal"
                    + LINE_JOINS
                    + " WHERE m.run_id = ? AND m.ordinal IN ("
                    + "   SELECT ordinal FROM run_match WHERE run_id = ? AND ordinal > ? ORDER BY ordinal LIMIT ?)"
                    + " ORDER BY m.ordinal, l.position";
    private static final String SELECT_EXCEPTIONS =
            "SELECT x.ordinal, x.kind, x.reason, x.detail, l.role, " + LINE_COLUMNS
                    + " FROM run_exception x"
                    + " JOIN run_exception_line l ON l.run_id = x.run_id AND l.exception_ordinal = x.ordinal"
                    + LINE_JOINS
                    + " WHERE x.run_id = ? AND x.ordinal IN ("
                    + "   SELECT ordinal FROM run_exception WHERE run_id = ? AND ordinal > ? ORDER BY ordinal LIMIT ?)"
                    + " ORDER BY x.ordinal, l.role, l.position";
    private static final String SELECT_CARRIED =
            "SELECT ledger_seq FROM run_carried_forward WHERE run_id = ? AND ledger_seq > ?"
                    + " ORDER BY ledger_seq LIMIT ?";
    private static final String SELECT_NOTES = "SELECT note FROM run_note WHERE run_id = ? ORDER BY ordinal";

    private final JdbcTemplate jdbc;

    public JdbcRunResultReader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public List<MatchView> matches(long runId, int afterOrdinal, int limit) {
        List<MatchRow> rows = jdbc.query(SELECT_MATCHES, (rs, n) -> new MatchRow(rs.getInt("ordinal"),
                rs.getString("tier"), rs.getInt("score_milli"), rs.getString("band"), rs.getString("reason"),
                line(rs)), runId, runId, afterOrdinal, limit);
        Map<Integer, List<MatchRow>> byOrdinal = new LinkedHashMap<>();
        for (MatchRow row : rows) {
            byOrdinal.computeIfAbsent(row.ordinal, k -> new ArrayList<>()).add(row);
        }
        List<MatchView> views = new ArrayList<>();
        for (List<MatchRow> group : byOrdinal.values()) {
            MatchRow first = group.get(0);
            views.add(new MatchView(first.ordinal, first.tier, first.scoreMilli, first.band, first.reason,
                    group.stream().map(r -> r.line).toList()));
        }
        return views;
    }

    @Override
    public List<ExceptionView> exceptions(long runId, int afterOrdinal, int limit) {
        List<ExceptionRow> rows = jdbc.query(SELECT_EXCEPTIONS, (rs, n) -> new ExceptionRow(rs.getInt("ordinal"),
                rs.getString("kind"), rs.getString("reason"), rs.getString("detail"), rs.getString("role"),
                line(rs)), runId, runId, afterOrdinal, limit);
        Map<Integer, List<ExceptionRow>> byOrdinal = new LinkedHashMap<>();
        for (ExceptionRow row : rows) {
            byOrdinal.computeIfAbsent(row.ordinal, k -> new ArrayList<>()).add(row);
        }
        List<ExceptionView> views = new ArrayList<>();
        for (List<ExceptionRow> group : byOrdinal.values()) {
            ExceptionRow first = group.get(0);
            views.add(new ExceptionView(first.ordinal, first.kind, first.reason, first.detail,
                    linesWithRole(group, "SUBJECT"), linesWithRole(group, "CANDIDATE")));
        }
        return views;
    }

    @Override
    public List<Long> carriedForward(long runId, long afterLedgerSeq, int limit) {
        return jdbc.query(SELECT_CARRIED, (rs, n) -> rs.getLong("ledger_seq"), runId, afterLedgerSeq, limit);
    }

    @Override
    public List<String> notes(long runId) {
        return jdbc.query(SELECT_NOTES, (rs, n) -> rs.getString("note"), runId);
    }

    private static List<LineView> linesWithRole(List<ExceptionRow> group, String role) {
        return group.stream().filter(r -> r.role.equals(role)).map(r -> r.line).toList();
    }

    private static LineView line(ResultSet rs) throws SQLException {
        long statementSeq = rs.getLong("statement_seq");
        boolean isStatement = !rs.wasNull();
        long seq = isStatement ? statementSeq : rs.getLong("ledger_seq");
        return new LineView(isStatement ? LineSource.STATEMENT : LineSource.LEDGER, seq, rs.getString("reference"),
                rs.getLong("amount_minor"), rs.getString("currency"),
                rs.getObject("value_date", java.time.LocalDate.class));
    }

    private record MatchRow(int ordinal, String tier, int scoreMilli, String band, String reason, LineView line) {
    }

    private record ExceptionRow(int ordinal, String kind, String reason, String detail, String role, LineView line) {
    }
}