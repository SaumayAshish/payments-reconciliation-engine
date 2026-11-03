package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.AuditLog;
import com.saumay.payments.domain.audit.AuditChain;
import com.saumay.payments.domain.audit.AuditChainChecker;
import com.saumay.payments.domain.audit.AuditDraft;
import com.saumay.payments.domain.audit.AuditEvent;
import com.saumay.payments.domain.audit.AuditHead;
import com.saumay.payments.domain.audit.AuditVerification;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * PostgreSQL adapter for {@link AuditLog}. Appending takes the chain advisory lock, reads the head, computes the next
 * event and inserts it; the lock is held until the surrounding transaction ends, so two writers can never build on
 * the same head and sequence numbers have no gaps even when transactions roll back. The table's own trigger repeats
 * the continuity check, so a writer that bypassed this class still cannot fork or skip.
 */
public final class JdbcAuditLog implements AuditLog {

    private static final int DEFAULT_VERIFY_PAGE_SIZE = 1_000;
    private static final String LOCK_CHAIN = "SELECT pg_advisory_xact_lock(7002, 1)";
    private static final String HEAD = "SELECT seq, current_hash FROM audit_event ORDER BY seq DESC LIMIT 1";
    private static final String INSERT =
            "INSERT INTO audit_event (seq, scheme, entity_type, entity_id, actor, action, before_state, after_state,"
                    + " occurred_at_micros, previous_hash, current_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String PAGE =
            "SELECT seq, scheme, entity_type, entity_id, actor, action, before_state, after_state,"
                    + " occurred_at_micros, previous_hash, current_hash"
                    + " FROM audit_event WHERE seq > ? ORDER BY seq LIMIT ?";

    private final JdbcTemplate jdbc;
    private final int verifyPageSize;

    public JdbcAuditLog(DataSource dataSource) {
        this(dataSource, DEFAULT_VERIFY_PAGE_SIZE);
    }

    JdbcAuditLog(DataSource dataSource, int verifyPageSize) {
        if (verifyPageSize < 1) {
            throw new IllegalArgumentException("verifyPageSize must be at least 1");
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.verifyPageSize = verifyPageSize;
    }

    @Override
    public AuditEvent append(AuditDraft draft) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "An audit event must be appended inside the transaction of the action it records");
        }
        jdbc.execute(LOCK_CHAIN);
        AuditEvent event = AuditChain.append(head(), draft);
        jdbc.update(INSERT, event.seq(), event.scheme(), event.entityType(), event.entityId(), event.actor(),
                event.action(), event.beforeState(), event.afterState(), event.occurredAtMicros(),
                event.previousHash(), event.currentHash());
        return event;
    }

    @Override
    public AuditHead head() {
        List<AuditHead> rows = jdbc.query(HEAD,
                (rs, rowNum) -> new AuditHead(rs.getLong("seq"), rs.getString("current_hash")));
        return rows.isEmpty() ? AuditChain.GENESIS : rows.get(0);
    }

    @Override
    public AuditVerification verify() {
        AuditChainChecker checker = new AuditChainChecker();
        long after = 0;
        long verified = 0;
        while (true) {
            List<StoredRow> page = jdbc.query(PAGE, (rs, rowNum) -> new StoredRow(rs.getLong("seq"),
                    rs.getInt("scheme"), rs.getString("entity_type"), rs.getString("entity_id"),
                    rs.getString("actor"), rs.getString("action"), rs.getString("before_state"),
                    rs.getString("after_state"), rs.getLong("occurred_at_micros"), rs.getString("previous_hash"),
                    rs.getString("current_hash")), after, verifyPageSize);
            for (StoredRow row : page) {
                AuditEvent event;
                try {
                    event = row.toEvent();
                } catch (IllegalArgumentException | NullPointerException malformed) {
                    return new AuditVerification.Failed(row.seq(), AuditVerification.FailureReason.HASH_MISMATCH,
                            verified);
                }
                if (!checker.accept(event)) {
                    return checker.result();
                }
                verified++;
                after = row.seq();
            }
            if (page.size() < verifyPageSize) {
                return checker.result();
            }
        }
    }

    /** A row as stored, before it is trusted: building the event checks its shape, which tampering can break. */
    private record StoredRow(long seq, int scheme, String entityType, String entityId, String actor, String action,
            String beforeState, String afterState, long occurredAtMicros, String previousHash, String currentHash) {

        AuditEvent toEvent() {
            return new AuditEvent(seq, scheme, entityType, entityId, actor, action, beforeState, afterState,
                    occurredAtMicros, previousHash, currentHash);
        }
    }
}