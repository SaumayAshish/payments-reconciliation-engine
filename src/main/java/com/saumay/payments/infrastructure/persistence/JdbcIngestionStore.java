package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.domain.model.CanonicalLine;
import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.ingestion.AcceptedLine;
import com.saumay.payments.ingestion.IngestionOutcome;
import com.saumay.payments.ingestion.IngestionRequest;
import com.saumay.payments.ingestion.IngestionStore;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL adapter for {@link IngestionStore}. One transaction per file. A transaction-scoped advisory lock on the
 * file identity serialises concurrent identical uploads; the unique constraint on (kind, file_hash) is the final
 * backstop (ADR-0002). Any failure rolls back the file row and every line together.
 */
public final class JdbcIngestionStore implements IngestionStore {

    private static final String FIND_FILE =
            "SELECT id, accepted_count, rejected_count FROM ingestion_file WHERE kind = ? AND file_hash = ?";
    private static final String INSERT_FILE =
            "INSERT INTO ingestion_file (kind, file_hash, format, byte_size, accepted_count, rejected_count)"
                    + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id";
    private static final String LINE_COLUMNS =
            "(ingestion_id, line_number, line_key, account, amount_minor, currency, value_date, booking_date,"
                    + " reference, reference_norm, counterparty, counterparty_norm, narrative)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_STATEMENT_LINE = "INSERT INTO statement_line " + LINE_COLUMNS;
    private static final String INSERT_LEDGER_ENTRY = "INSERT INTO ledger_entry " + LINE_COLUMNS;
    private static final String INSERT_REJECTED =
            "INSERT INTO rejected_line (ingestion_id, line_number, error_code, error_message, raw_excerpt)"
                    + " VALUES (?, ?, ?, ?, ?)";
    private static final int BATCH_SIZE = 1_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcIngestionStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public IngestionOutcome store(IngestionRequest request) {
        return Objects.requireNonNull(transaction.execute(status -> storeInTransaction(request)));
    }

    private IngestionOutcome storeInTransaction(IngestionRequest request) {
        lock(lockKey(request.kind(), request.fileHash()));

        List<IngestionOutcome> existing = jdbc.query(FIND_FILE,
                (rs, row) -> new IngestionOutcome(rs.getLong("id"), rs.getInt("accepted_count"),
                        rs.getInt("rejected_count"), true),
                request.kind().name(), request.fileHash().hex());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        final long ingestionId = Objects.requireNonNull(jdbc.queryForObject(INSERT_FILE, Long.class,
                request.kind().name(), request.fileHash().hex(), request.format().name(), request.byteSize(),
                request.accepted().size(), request.rejected().size()));

        String insertLine = switch (request.kind()) {
            case STATEMENT -> INSERT_STATEMENT_LINE;
            case LEDGER -> INSERT_LEDGER_ENTRY;
        };
        if (!request.accepted().isEmpty()) {
            jdbc.batchUpdate(insertLine, request.accepted(), BATCH_SIZE, (ps, accepted) -> {
                CanonicalLine line = accepted.line();
                ps.setLong(1, ingestionId);
                ps.setInt(2, accepted.lineNumber());
                ps.setString(3, line.lineKey().value());
                ps.setString(4, line.account());
                ps.setLong(5, line.amount().minorUnits());
                ps.setString(6, line.amount().currency().code());
                ps.setObject(7, line.valueDate());
                ps.setObject(8, line.bookingDate(), Types.DATE);
                ps.setString(9, line.reference());
                ps.setString(10, line.referenceNorm());
                ps.setString(11, line.counterparty());
                ps.setString(12, line.counterpartyNorm());
                ps.setString(13, line.narrative());
            });
        }
        if (!request.rejected().isEmpty()) {
            jdbc.batchUpdate(INSERT_REJECTED, request.rejected(), BATCH_SIZE, (ps, rejected) -> {
                ps.setLong(1, ingestionId);
                ps.setInt(2, rejected.lineNumber());
                ps.setString(3, rejected.code().name());
                ps.setString(4, rejected.message());
                ps.setString(5, rejected.rawExcerpt());
            });
        }
        return new IngestionOutcome(ingestionId, request.accepted().size(), request.rejected().size(), false);
    }

    private void lock(long key) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                ps.setLong(1, key);
                ps.execute();
            }
            return null;
        });
    }

    /** First 64 bits of SHA-256 over the file identity: stable across restarts and machines. */
    static long lockKey(LineSource kind, ContentHash fileHash) {
        String digest = ContentHash.ofUtf8(kind.name() + ":" + fileHash.hex()).hex();
        return Long.parseUnsignedLong(digest.substring(0, 16), 16);
    }
}