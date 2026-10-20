package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.domain.model.CanonicalLine;
import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.ingestion.AcceptedLine;
import com.saumay.payments.ingestion.EntryContent;
import com.saumay.payments.ingestion.IngestionOutcome;
import com.saumay.payments.ingestion.IngestionRequest;
import com.saumay.payments.ingestion.IngestionStore;
import com.saumay.payments.ingestion.LedgerEntryIdResolver;
import com.saumay.payments.ingestion.RejectedLine;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL adapter for {@link IngestionStore}. One transaction per file. A transaction-scoped advisory lock on the
 * file identity serialises concurrent identical uploads; the unique constraint on (kind, file_hash) is the final
 * backstop (ADR-0002). Ledger files that carry entry ids also take a second advisory lock, always after the file
 * lock, so entry ids are resolved one file at a time; the unique index on entry_id is the backstop there.
 * Any failure rolls back the file row and every line together.
 */
public final class JdbcIngestionStore implements IngestionStore {

    private static final String FIND_FILE =
            "SELECT id, accepted_count, rejected_count FROM ingestion_file WHERE kind = ? AND file_hash = ?";
    private static final String INSERT_FILE =
            "INSERT INTO ingestion_file (kind, file_hash, format, byte_size, accepted_count, rejected_count)"
                    + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id";
    private static final String LINE_COLUMNS =
            "ingestion_id, line_number, line_key, account, amount_minor, currency, value_date, booking_date,"
                    + " reference, reference_norm, counterparty, counterparty_norm, narrative";
    private static final String INSERT_STATEMENT_LINE =
            "INSERT INTO statement_line (" + LINE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_LEDGER_ENTRY =
            "INSERT INTO ledger_entry (" + LINE_COLUMNS + ", entry_id)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_REJECTED =
            "INSERT INTO rejected_line (ingestion_id, line_number, error_code, error_message, raw_excerpt)"
                    + " VALUES (?, ?, ?, ?, ?)";
    private static final String FIND_ENTRIES =
            "SELECT entry_id, account, currency, amount_minor, value_date, booking_date, reference, counterparty,"
                    + " narrative FROM ledger_entry WHERE entry_id = ANY (?)";
    private static final int BATCH_SIZE = 1_000;
    private static final long ENTRY_ID_REGISTRY_LOCK = keyOf("LEDGER_ENTRY_ID_REGISTRY");

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

        List<AcceptedLine> accepted = request.accepted();
        List<RejectedLine> rejected = request.rejected();
        if (request.kind() == LineSource.LEDGER && accepted.stream().anyMatch(a -> a.entryId() != null)) {
            lock(ENTRY_ID_REGISTRY_LOCK);
            LedgerEntryIdResolver.Resolution resolution =
                    LedgerEntryIdResolver.resolve(accepted, rejected, findKnownEntries(accepted));
            accepted = resolution.accepted();
            rejected = resolution.rejected();
        }
        final List<AcceptedLine> linesToInsert = accepted;
        final List<RejectedLine> rejectedToInsert = rejected;

        final long ingestionId = Objects.requireNonNull(jdbc.queryForObject(INSERT_FILE, Long.class,
                request.kind().name(), request.fileHash().hex(), request.format().name(), request.byteSize(),
                linesToInsert.size(), rejectedToInsert.size()));

        final boolean ledger = request.kind() == LineSource.LEDGER;
        if (!linesToInsert.isEmpty()) {
            jdbc.batchUpdate(ledger ? INSERT_LEDGER_ENTRY : INSERT_STATEMENT_LINE, linesToInsert, BATCH_SIZE,
                    (ps, line) -> {
                        CanonicalLine c = line.line();
                        ps.setLong(1, ingestionId);
                        ps.setInt(2, line.lineNumber());
                        ps.setString(3, c.lineKey().value());
                        ps.setString(4, c.account());
                        ps.setLong(5, c.amount().minorUnits());
                        ps.setString(6, c.amount().currency().code());
                        ps.setObject(7, c.valueDate());
                        ps.setObject(8, c.bookingDate(), Types.DATE);
                        ps.setString(9, c.reference());
                        ps.setString(10, c.referenceNorm());
                        ps.setString(11, c.counterparty());
                        ps.setString(12, c.counterpartyNorm());
                        ps.setString(13, c.narrative());
                        if (ledger) {
                            ps.setString(14, line.entryId());
                        }
                    });
        }
        if (!rejectedToInsert.isEmpty()) {
            jdbc.batchUpdate(INSERT_REJECTED, rejectedToInsert, BATCH_SIZE, (ps, bad) -> {
                ps.setLong(1, ingestionId);
                ps.setInt(2, bad.lineNumber());
                ps.setString(3, bad.code().name());
                ps.setString(4, bad.message());
                ps.setString(5, bad.rawExcerpt());
            });
        }
        return new IngestionOutcome(ingestionId, linesToInsert.size(), rejectedToInsert.size(), false);
    }

    private Map<String, EntryContent> findKnownEntries(List<AcceptedLine> accepted) {
        String[] ids = accepted.stream().map(AcceptedLine::entryId).filter(Objects::nonNull).distinct()
                .toArray(String[]::new);
        return jdbc.execute((ConnectionCallback<Map<String, EntryContent>>) connection -> {
            Map<String, EntryContent> known = new HashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(FIND_ENTRIES)) {
                ps.setArray(1, connection.createArrayOf("text", ids));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        known.put(rs.getString("entry_id"), new EntryContent(
                                rs.getString("account"),
                                rs.getString("currency"),
                                rs.getLong("amount_minor"),
                                rs.getObject("value_date", LocalDate.class),
                                rs.getObject("booking_date", LocalDate.class),
                                rs.getString("reference"),
                                rs.getString("counterparty"),
                                rs.getString("narrative")));
                    }
                }
            }
            return known;
        });
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
        return keyOf(kind.name() + ":" + fileHash.hex());
    }

    private static long keyOf(String identity) {
        String digest = ContentHash.ofUtf8(identity).hex();
        return Long.parseUnsignedLong(digest.substring(0, 16), 16);
    }
}
