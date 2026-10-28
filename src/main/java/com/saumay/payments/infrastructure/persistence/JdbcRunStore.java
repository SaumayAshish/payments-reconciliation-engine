package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.RunErrorCode;
import com.saumay.payments.application.RunHeader;
import com.saumay.payments.application.RunInputs;
import com.saumay.payments.application.RunRejectedException;
import com.saumay.payments.application.RunRequest;
import com.saumay.payments.application.RunStatus;
import com.saumay.payments.application.RunStore;
import com.saumay.payments.domain.model.CanonicalLine;
import com.saumay.payments.domain.model.LineKey;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.domain.money.CurrencyCode;
import com.saumay.payments.domain.money.Money;
import com.saumay.payments.matching.Match;
import com.saumay.payments.matching.MatchableLine;
import com.saumay.payments.matching.MatchingConfig;
import com.saumay.payments.matching.RunException;
import com.saumay.payments.matching.RunResult;
import com.saumay.payments.matching.RunSummary;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL adapter for {@link RunStore}.
 *
 * <p>{@link #create} runs in its own short transaction: it takes the cut-off lock exclusively, reads the highest
 * ledger sequence, writes the run as CREATED and commits. The lock is therefore held only for that moment, never
 * while matching. {@link #complete} writes all result rows and flips the status in one transaction, so a run is
 * never COMPLETED with half its output.
 */
public final class JdbcRunStore implements RunStore {

    private static final int BATCH_SIZE = 1_000;
    private static final int MAX_FAILURE_REASON = 500;

    private static final String SELECT_INGESTIONS = "SELECT id, kind FROM ingestion_file WHERE id = ANY (?)";
    private static final String MAX_LEDGER_SEQ = "SELECT COALESCE(MAX(seq), 0) FROM ledger_entry";
    private static final String MAX_CLAIM_SEQ = "SELECT COALESCE(MAX(seq), 0) FROM line_claim";
    private static final String SELECT_CLAIM_CUTOFF = "SELECT claim_cutoff_seq FROM reconciliation_run WHERE id = ?";
    // A line is free for a run unless a claim at or below the run's claim cut-off was still unreleased at that
    // point (ADR-0009). The placeholders are the claim cut-off, twice; the line table is aliased t.
    private static final String UNCLAIMED_AS_OF =
            " AND NOT EXISTS (SELECT 1 FROM line_claim c WHERE c.action = 'CLAIM' AND c.%s = t.seq AND c.seq <= ?"
                    + " AND NOT EXISTS (SELECT 1 FROM line_claim r WHERE r.releases_seq = c.seq AND r.seq <= ?))";
    private static final String CLAIM_AUTO_CONFIRMED =
            "INSERT INTO line_claim (action, statement_seq, ledger_seq, source, run_id, run_match_ordinal)"
                    + " SELECT 'CLAIM', l.statement_seq, l.ledger_seq, 'RUN_MATCH', l.run_id, l.match_ordinal"
                    + " FROM run_match_line l JOIN run_match m ON m.run_id = l.run_id AND m.ordinal = l.match_ordinal"
                    + " WHERE l.run_id = ? AND m.band = 'AUTO_CONFIRM' ORDER BY l.match_ordinal, l.position";
    private static final String INSERT_RUN =
            "INSERT INTO reconciliation_run (business_date, status, ledger_cutoff_seq, claim_cutoff_seq, auto_threshold_milli,"
                    + " review_threshold_milli, date_window_business_days, lookback_business_days,"
                    + " max_one_to_many_candidates, fuzzy_min_similarity_milli, fuzzy_counterparty_weight_milli)"
                    + " VALUES (?, 'CREATED', ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
    private static final String INSERT_BATCH =
            "INSERT INTO reconciliation_run_statement_batch (run_id, ingestion_id) VALUES (?, ?)";
    private static final String SELECT_RUN =
            "SELECT business_date, status, ledger_cutoff_seq, auto_threshold_milli, review_threshold_milli,"
                    + " date_window_business_days, lookback_business_days, max_one_to_many_candidates,"
                    + " fuzzy_min_similarity_milli, fuzzy_counterparty_weight_milli, created_at, started_at,"
                    + " completed_at, duration_millis, output_hash, statement_line_count, ledger_entry_count,"
                    + " match_count, auto_match_count, review_match_count, ambiguous_group_count, exception_count,"
                    + " carried_forward_count, truncation_note_count, failure_reason"
                    + " FROM reconciliation_run WHERE id = ?";
    private static final String SELECT_BATCHES =
            "SELECT ingestion_id FROM reconciliation_run_statement_batch WHERE run_id = ? ORDER BY ingestion_id";
    private static final String LINE_COLUMNS =
            "seq, account, amount_minor, currency, value_date, booking_date, reference, reference_norm,"
                    + " counterparty, counterparty_norm, narrative, line_key";
    private static final String SELECT_STATEMENTS =
            "SELECT " + LINE_COLUMNS + " FROM statement_line t WHERE t.ingestion_id = ANY (?)"
                    + String.format(UNCLAIMED_AS_OF, "statement_seq") + " ORDER BY t.seq";
    private static final String SELECT_LEDGER =
            "SELECT " + LINE_COLUMNS + " FROM ledger_entry t WHERE t.seq <= ?"
                    + String.format(UNCLAIMED_AS_OF, "ledger_seq") + " ORDER BY t.seq";
    private static final String MARK_RUNNING =
            "UPDATE reconciliation_run SET status = 'RUNNING', started_at = now() WHERE id = ? AND status = 'CREATED'";
    private static final String MARK_COMPLETED =
            "UPDATE reconciliation_run SET status = 'COMPLETED', completed_at = now(), duration_millis = ?,"
                    + " output_hash = ?, statement_line_count = ?, ledger_entry_count = ?, match_count = ?,"
                    + " auto_match_count = ?, review_match_count = ?, ambiguous_group_count = ?, exception_count = ?,"
                    + " carried_forward_count = ?, truncation_note_count = ? WHERE id = ? AND status = 'RUNNING'";
    private static final String MARK_FAILED =
            "UPDATE reconciliation_run SET status = 'FAILED', completed_at = now(), failure_reason = ?"
                    + " WHERE id = ? AND status IN ('CREATED', 'RUNNING')";
    private static final String INSERT_MATCH =
            "INSERT INTO run_match (run_id, ordinal, tier, score_milli, band, reason) VALUES (?, ?, ?, ?, ?, ?)";
    private static final String INSERT_MATCH_LINE =
            "INSERT INTO run_match_line (run_id, match_ordinal, position, statement_seq, ledger_seq)"
                    + " VALUES (?, ?, ?, ?, ?)";
    private static final String INSERT_EXCEPTION =
            "INSERT INTO run_exception (run_id, ordinal, kind, natural_key, reason, detail)"
                    + " VALUES (?, ?, ?, ?, ?, ?)";
    private static final String INSERT_EXCEPTION_LINE =
            "INSERT INTO run_exception_line (run_id, exception_ordinal, role, position, statement_seq, ledger_seq)"
                    + " VALUES (?, ?, ?, ?, ?, ?)";
    private static final String INSERT_CARRIED =
            "INSERT INTO run_carried_forward (run_id, ledger_seq) VALUES (?, ?)";
    private static final String INSERT_NOTE = "INSERT INTO run_note (run_id, ordinal, note) VALUES (?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcRunStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public RunHeader create(RunRequest request) {
        return Objects.requireNonNull(transaction.execute(status -> createInTransaction(request)));
    }

    private RunHeader createInTransaction(RunRequest request) {
        AdvisoryLocks.exclusive(jdbc, AdvisoryLocks.RUN_CUTOFF);
        requireStatementIngestions(request.statementIngestionIds());

        long cutoff = Objects.requireNonNull(jdbc.queryForObject(MAX_LEDGER_SEQ, Long.class));
        long claimCutoff = Objects.requireNonNull(jdbc.queryForObject(MAX_CLAIM_SEQ, Long.class));
        MatchingConfig c = request.config();
        long runId = Objects.requireNonNull(jdbc.queryForObject(INSERT_RUN, Long.class,
                request.businessDate(), cutoff, claimCutoff, c.autoThresholdMilli(), c.reviewThresholdMilli(),
                c.dateWindowBusinessDays(), c.lookbackBusinessDays(), c.maxOneToManyCandidates(),
                c.fuzzyMinSimilarityMilli(), c.fuzzyCounterpartyWeightMilli()));
        jdbc.batchUpdate(INSERT_BATCH, request.statementIngestionIds(), BATCH_SIZE, (ps, ingestionId) -> {
            ps.setLong(1, runId);
            ps.setLong(2, ingestionId);
        });
        return find(runId).orElseThrow(() -> new IllegalStateException("Run vanished inside its own transaction"));
    }

    private void requireStatementIngestions(List<Long> ids) {
        Map<Long, String> kinds = jdbc.execute((ConnectionCallback<Map<Long, String>>) connection -> {
            Map<Long, String> found = new HashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(SELECT_INGESTIONS)) {
                ps.setArray(1, bigintArray(connection, ids));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        found.put(rs.getLong("id"), rs.getString("kind"));
                    }
                }
            }
            return found;
        });
        for (Long id : ids) {
            String kind = kinds.get(id);
            if (kind == null) {
                throw new RunRejectedException(RunErrorCode.UNKNOWN_INGESTION,
                        "No ingestion exists with id " + id);
            }
            if (!LineSource.STATEMENT.name().equals(kind)) {
                throw new RunRejectedException(RunErrorCode.NOT_A_STATEMENT_INGESTION,
                        "Ingestion " + id + " is not a statement ingestion");
            }
        }
    }

    @Override
    public Optional<RunHeader> find(long runId) {
        List<RunHeader> found = jdbc.query(SELECT_RUN, (rs, row) -> header(runId, rs), runId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<Long> batches = jdbc.query(SELECT_BATCHES, (rs, row) -> rs.getLong("ingestion_id"), runId);
        RunHeader h = found.get(0);
        return Optional.of(new RunHeader(h.id(), h.businessDate(), h.status(), h.config(), h.ledgerCutoffSeq(),
                batches, h.createdAt(), h.startedAt(), h.completedAt(), h.durationMillis(), h.outputHash(),
                h.summary(), h.failureReason()));
    }

    private static RunHeader header(long runId, ResultSet rs) throws SQLException {
        MatchingConfig config = new MatchingConfig(rs.getInt("auto_threshold_milli"),
                rs.getInt("review_threshold_milli"), rs.getInt("date_window_business_days"),
                rs.getInt("lookback_business_days"), rs.getInt("max_one_to_many_candidates"),
                rs.getInt("fuzzy_min_similarity_milli"), rs.getInt("fuzzy_counterparty_weight_milli"));
        long duration = rs.getLong("duration_millis");
        Long durationMillis = rs.wasNull() ? null : duration;
        int matchCount = rs.getInt("match_count");
        RunSummary summary = rs.wasNull() ? null : new RunSummary(
                rs.getInt("statement_line_count"), rs.getInt("ledger_entry_count"), matchCount,
                rs.getInt("auto_match_count"), rs.getInt("review_match_count"),
                rs.getInt("ambiguous_group_count"), rs.getInt("exception_count"),
                rs.getInt("carried_forward_count"), rs.getInt("truncation_note_count"));
        String hash = rs.getString("output_hash");
        return new RunHeader(runId, rs.getObject("business_date", LocalDate.class),
                RunStatus.valueOf(rs.getString("status")), config, rs.getLong("ledger_cutoff_seq"), List.of(),
                instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "completed_at"),
                durationMillis, hash == null ? null : hash.trim(), summary, rs.getString("failure_reason"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    @Override
    public RunInputs loadInputs(long runId) {
        RunHeader run = find(runId).orElseThrow(
                () -> new RunRejectedException(RunErrorCode.RUN_NOT_FOUND, "No run exists with id " + runId));
        long claimCutoff = Objects.requireNonNull(jdbc.queryForObject(SELECT_CLAIM_CUTOFF, Long.class, runId));
        List<MatchableLine> statements = jdbc.execute((ConnectionCallback<List<MatchableLine>>) connection -> {
            try (PreparedStatement ps = connection.prepareStatement(SELECT_STATEMENTS)) {
                ps.setArray(1, bigintArray(connection, run.statementIngestionIds()));
                ps.setLong(2, claimCutoff);
                ps.setLong(3, claimCutoff);
                return readLines(ps, LineSource.STATEMENT);
            }
        });
        List<MatchableLine> ledger = jdbc.execute((ConnectionCallback<List<MatchableLine>>) connection -> {
            try (PreparedStatement ps = connection.prepareStatement(SELECT_LEDGER)) {
                ps.setLong(1, run.ledgerCutoffSeq());
                ps.setLong(2, claimCutoff);
                ps.setLong(3, claimCutoff);
                return readLines(ps, LineSource.LEDGER);
            }
        });
        return new RunInputs(run.id(), run.businessDate(), run.config(), statements, ledger);
    }

    private static List<MatchableLine> readLines(PreparedStatement ps, LineSource source) throws SQLException {
        List<MatchableLine> lines = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lines.add(new MatchableLine(rs.getLong("seq"), new CanonicalLine(
                        source,
                        rs.getString("account"),
                        Money.of(rs.getLong("amount_minor"), CurrencyCode.of(rs.getString("currency"))),
                        rs.getObject("value_date", LocalDate.class),
                        rs.getObject("booking_date", LocalDate.class),
                        rs.getString("reference"),
                        rs.getString("reference_norm"),
                        rs.getString("counterparty"),
                        rs.getString("counterparty_norm"),
                        rs.getString("narrative"),
                        new LineKey(rs.getString("line_key")))));
            }
        }
        return lines;
    }

    @Override
    public void markRunning(long runId) {
        requireOneRow(jdbc.update(MARK_RUNNING, runId), runId, "start");
    }

    @Override
    public void fail(long runId, String reason) {
        String safe = reason.length() > MAX_FAILURE_REASON ? reason.substring(0, MAX_FAILURE_REASON) : reason;
        requireOneRow(jdbc.update(MARK_FAILED, safe, runId), runId, "fail");
    }

    @Override
    public void complete(long runId, RunResult result, long durationMillis) {
        transaction.executeWithoutResult(status -> {
            // The status change comes first: a run that is not RUNNING is refused before any row is written.
            RunSummary s = result.summary();
            requireOneRow(jdbc.update(MARK_COMPLETED, durationMillis, result.outputHash().hex(),
                            s.statementLines(), s.ledgerEntries(), s.matches(), s.autoConfirmedMatches(), s.reviewMatches(),
                            s.ambiguousGroups(), s.exceptions(), s.carriedForward(), s.truncationNotes(), runId),
                    runId, "complete");
            writeMatches(runId, result);
            claimAutoConfirmedLines(runId);
            writeExceptions(runId, result);
            writeCarriedAndNotes(runId, result);
        });
    }

    private void writeMatches(long runId, RunResult result) {
        List<Match> matches = result.pipeline().matches();
        List<IndexedMatch> indexed = new ArrayList<>();
        List<LineRow> lines = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            Match match = matches.get(i);
            int ordinal = i + 1;
            indexed.add(new IndexedMatch(ordinal, match));
            addLines(lines, ordinal, "", sortedBoth(match.statementSide(), match.ledgerSide()));
        }
        jdbc.batchUpdate(INSERT_MATCH, indexed, BATCH_SIZE, (ps, m) -> {
            ps.setLong(1, runId);
            ps.setInt(2, m.ordinal());
            ps.setString(3, m.match().tier().name());
            ps.setInt(4, m.match().score().milli());
            ps.setString(5, m.match().band().name());
            ps.setString(6, m.match().reason());
        });
        jdbc.batchUpdate(INSERT_MATCH_LINE, lines, BATCH_SIZE, (ps, l) -> {
            ps.setLong(1, runId);
            ps.setInt(2, l.ordinal());
            ps.setInt(3, l.position());
            setSeqs(ps, 4, l.line());
        });
    }

    private void writeExceptions(long runId, RunResult result) {
        List<RunException> exceptions = result.classification().exceptions();
        List<IndexedException> indexed = new ArrayList<>();
        List<LineRow> lines = new ArrayList<>();
        for (int i = 0; i < exceptions.size(); i++) {
            RunException exception = exceptions.get(i);
            int ordinal = i + 1;
            indexed.add(new IndexedException(ordinal, exception));
            addLines(lines, ordinal, "SUBJECT", exception.subjects());
            addLines(lines, ordinal, "CANDIDATE", exception.candidates());
        }
        jdbc.batchUpdate(INSERT_EXCEPTION, indexed, BATCH_SIZE, (ps, e) -> {
            ps.setLong(1, runId);
            ps.setInt(2, e.ordinal());
            ps.setString(3, e.exception().kind().name());
            ps.setString(4, e.exception().naturalKey());
            ps.setString(5, e.exception().reason());
            ps.setString(6, e.exception().detail());
        });
        jdbc.batchUpdate(INSERT_EXCEPTION_LINE, lines, BATCH_SIZE, (ps, l) -> {
            ps.setLong(1, runId);
            ps.setInt(2, l.ordinal());
            ps.setString(3, l.role());
            ps.setInt(4, l.position());
            setSeqs(ps, 5, l.line());
        });
    }

    private void writeCarriedAndNotes(long runId, RunResult result) {
        jdbc.batchUpdate(INSERT_CARRIED, result.classification().carriedForward(), BATCH_SIZE, (ps, line) -> {
            ps.setLong(1, runId);
            ps.setLong(2, line.seq());
        });
        List<String> notes = result.pipeline().notes().stream().sorted().toList();
        List<IndexedNote> indexed = new ArrayList<>();
        for (int i = 0; i < notes.size(); i++) {
            indexed.add(new IndexedNote(i + 1, notes.get(i)));
        }
        jdbc.batchUpdate(INSERT_NOTE, indexed, BATCH_SIZE, (ps, n) -> {
            ps.setLong(1, runId);
            ps.setInt(2, n.ordinal());
            ps.setString(3, n.note());
        });
    }

    // ---- helpers ----

    private record IndexedMatch(int ordinal, Match match) {
    }

    private record IndexedException(int ordinal, RunException exception) {
    }

    private record IndexedNote(int ordinal, String note) {
    }

    /** One line of a match or exception. {@code role} is empty for match lines. */
    private record LineRow(int ordinal, String role, int position, MatchableLine line) {
    }

    private static void addLines(List<LineRow> out, int ordinal, String role, List<MatchableLine> lines) {
        for (int i = 0; i < lines.size(); i++) {
            out.add(new LineRow(ordinal, role, i + 1, lines.get(i)));
        }
    }

    /** Statement-side lines first, then ledger-side, each in canonical order, so row positions are deterministic. */
    private static List<MatchableLine> sortedBoth(List<MatchableLine> statements, List<MatchableLine> ledger) {
        List<MatchableLine> all = new ArrayList<>(statements.stream().sorted(MatchableLine.CANONICAL_ORDER).toList());
        all.addAll(ledger.stream().sorted(MatchableLine.CANONICAL_ORDER).toList());
        return all;
    }

    /** Sets the (statement_seq, ledger_seq) pair: exactly one is non-null, which the schema also enforces. */
    private static void setSeqs(PreparedStatement ps, int firstIndex, MatchableLine line) throws SQLException {
        boolean statement = line.line().source() == LineSource.STATEMENT;
        if (statement) {
            ps.setLong(firstIndex, line.seq());
            ps.setNull(firstIndex + 1, Types.BIGINT);
        } else {
            ps.setNull(firstIndex, Types.BIGINT);
            ps.setLong(firstIndex + 1, line.seq());
        }
    }

    /**
     * Claims every line of this run's auto-confirmed matches (ADR-0009). Review proposals claim nothing: they are
     * suggestions until a person resolves them. The cut-off lock is held shared until commit, so a run that reads
     * its claim cut-off waits for this transaction. If another run claimed one of the lines first the database
     * refuses the insert, the whole completion rolls back and the run ends FAILED instead of matching twice.
     */
    private void claimAutoConfirmedLines(long runId) {
        AdvisoryLocks.shared(jdbc, AdvisoryLocks.RUN_CUTOFF);
        jdbc.update(CLAIM_AUTO_CONFIRMED, runId);
    }

    private static Array bigintArray(Connection connection, List<Long> ids) throws SQLException {
        return connection.createArrayOf("bigint", ids.toArray(Long[]::new));
    }

    private static void requireOneRow(int updated, long runId, String action) {
        if (updated != 1) {
            throw new IllegalStateException("Run " + runId + " cannot " + action + " from its current status");
        }
    }
}