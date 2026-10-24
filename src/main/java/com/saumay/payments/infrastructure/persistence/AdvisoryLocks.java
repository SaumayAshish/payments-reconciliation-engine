package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.domain.model.ContentHash;
import java.sql.PreparedStatement;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL advisory locks used by more than one adapter. All are transaction-scoped: they are released at commit or
 * rollback, so a crashed process can never leave one behind.
 *
 * <p>{@link #RUN_CUTOFF} orders ingestion against the start of a run (ADR-0003 amendment). Every ingestion
 * transaction holds it shared for its whole life, so ingestions never wait for each other. A run takes it exclusively
 * for a moment, which waits for every ingestion in flight and holds new ones back until the run has read the
 * highest committed ledger sequence.
 */
final class AdvisoryLocks {

    static final long RUN_CUTOFF = keyOf("RUN_LEDGER_CUTOFF");

    private AdvisoryLocks() {
    }

    static void shared(JdbcTemplate jdbc, long key) {
        acquire(jdbc, "SELECT pg_advisory_xact_lock_shared(?)", key);
    }

    static void exclusive(JdbcTemplate jdbc, long key) {
        acquire(jdbc, "SELECT pg_advisory_xact_lock(?)", key);
    }

    /** First 64 bits of SHA-256 over the identity: stable across restarts and machines. */
    static long keyOf(String identity) {
        String digest = ContentHash.ofUtf8(identity).hex();
        return Long.parseUnsignedLong(digest.substring(0, 16), 16);
    }

    private static void acquire(JdbcTemplate jdbc, String sql, long key) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, key);
                ps.execute();
            }
            return null;
        });
    }
}