package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.audit.AuditChain;
import com.saumay.payments.domain.audit.AuditDraft;
import com.saumay.payments.domain.audit.AuditEvent;
import com.saumay.payments.domain.audit.AuditVerification;
import com.saumay.payments.domain.audit.AuditVerification.FailureReason;
import com.saumay.payments.domain.audit.CanonicalJson;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the audit chain on PostgreSQL 17 (ADR-0004, ADR-0010): appends form one unbroken chain even under
 * concurrency and rollbacks, the database refuses to edit, delete or truncate history and refuses an event that does
 * not continue the chain, and the verifier names the exact position of every kind of tampering. Tampering is done the
 * only way it can be: with the guard triggers switched off, as a database owner could.
 */
@Testcontainers
class AuditLogIT {

    private static final String RESTRICT_VIOLATION = "23001";
    private static final String CHECK_VIOLATION = "23514";
    private static final int PAGE = 7;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static JdbcAuditLog log;

    @BeforeAll
    static void migrateAndConnect() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setMaximumPoolSize(24);
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        log = new JdbcAuditLog(dataSource, PAGE);
    }

    @AfterAll
    static void disconnect() {
        dataSource.close();
    }

    @BeforeEach
    void startWithAnEmptyChain() {
        withGuardsOff(() -> jdbc.execute("TRUNCATE audit_event"));
    }

    @Test
    void anEmptyChainVerifiesAndItsHeadIsTheGenesis() {
        assertEquals(new AuditVerification.Ok(0, 0, AuditChain.GENESIS_HASH), log.verify());
        assertEquals(AuditChain.GENESIS, log.head());
    }

    @Test
    void appendedEventsFormOneChainThatVerifiesAcrossSeveralPages() {
        List<AuditEvent> events = appendAll(20);
        assertEquals(1L, events.get(0).seq());
        assertEquals(AuditChain.GENESIS_HASH, events.get(0).previousHash());
        for (int i = 1; i < events.size(); i++) {
            assertEquals(events.get(i - 1).currentHash(), events.get(i).previousHash());
        }
        AuditEvent last = events.get(19);
        assertEquals(new AuditVerification.Ok(20, 20, last.currentHash()), log.verify());
        assertEquals(AuditChain.headOf(last), log.head());
    }

    @Test
    void aChainWhoseLengthIsAWholeNumberOfPagesStillVerifies() {
        List<AuditEvent> events = appendAll(2 * PAGE);
        assertEquals(new AuditVerification.Ok(2 * PAGE, 2 * PAGE, events.get(2 * PAGE - 1).currentHash()),
                log.verify());
    }

    @Test
    void statesComeBackExactlyAsTheyWereHashedIncludingNonAsciiAndEscapes() {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("owner", "zo" + (char) 0xEB);
        after.put("note", "line one\nline \"two\"\t" + (char) 1 + (char) 0x20AC);
        after.put("amountMinor", 1250L);
        String state = CanonicalJson.write(after);
        AuditEvent event = append(new AuditDraft("exception_case", "9", "alice", "CASE_ASSIGNED", "null", state, 5L));
        assertEquals(state, jdbc.queryForObject("SELECT after_state FROM audit_event WHERE seq = 1", String.class));
        assertEquals(new AuditVerification.Ok(1, 1, event.currentHash()), log.verify());
    }

    @Test
    void appendingOutsideATransactionIsRefusedAndWritesNothing() {
        assertThrows(IllegalStateException.class, () -> log.append(draft(1)));
        assertEquals(0L, count());
    }

    @Test
    void aRolledBackAppendLeavesNeitherAnEventNorAGapInTheNumbers() {
        append(draft(1));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            log.append(draft(2));
            throw new IllegalStateException("the action failed after its audit event was appended");
        }));
        assertEquals(1L, count());
        AuditEvent next = append(draft(3));
        assertEquals(2L, next.seq());
        assertEquals(new AuditVerification.Ok(2, 2, next.currentHash()), log.verify());
    }

    @Test
    void theDatabaseRefusesToEditDeleteOrTruncateHistory() {
        append(draft(1));
        assertEquals(RESTRICT_VIOLATION, sqlState("UPDATE audit_event SET actor = 'mallory'"));
        assertEquals(RESTRICT_VIOLATION, sqlState("DELETE FROM audit_event"));
        assertEquals(RESTRICT_VIOLATION, sqlState("TRUNCATE audit_event"));
        assertEquals(1L, count());
        assertTrue(log.verify() instanceof AuditVerification.Ok);
    }

    @Test
    void aRawInsertMustContinueTheChainEvenWhenItBypassesTheStore() {
        String other = "a".repeat(64);
        assertEquals(CHECK_VIOLATION, tryInsert(raw(5, AuditChain.GENESIS_HASH, other)), "skipping ahead");
        assertEquals(CHECK_VIOLATION, tryInsert(raw(1, other, "b".repeat(64))), "not linked to the genesis hash");
        assertEquals(0L, count());

        AuditEvent first = AuditChain.append(AuditChain.GENESIS, draft(1));
        AuditEvent second = AuditChain.append(AuditChain.headOf(first), draft(2));
        assertNull(tryInsert(first));
        assertNull(tryInsert(second));
        AuditEvent fork = AuditChain.append(AuditChain.headOf(first), draft(3));
        assertEquals(CHECK_VIOLATION, tryInsert(fork), "a second event on an already extended head");
        assertEquals(2L, count());
        assertEquals(new AuditVerification.Ok(2, 2, second.currentHash()), log.verify());
    }

    @Test
    void theEntityHistoryIndexExists() {
        Long found = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'audit_event' AND indexname = 'ix_audit_entity'",
                Long.class);
        assertEquals(1L, found);
    }

    @Test
    void anEditedRowIsReportedAsAHashMismatchAtExactlyThatSeq() {
        appendAll(6);
        withGuardsOff(() -> jdbc.update("UPDATE audit_event SET after_state = '{\"n\":999}' WHERE seq = 3"));
        assertEquals(new AuditVerification.Failed(3, FailureReason.HASH_MISMATCH, 2), log.verify());
    }

    @Test
    void aDeletedRowIsReportedAsASequenceGapAtTheMissingSeq() {
        appendAll(6);
        withGuardsOff(() -> jdbc.update("DELETE FROM audit_event WHERE seq = 3"));
        assertEquals(new AuditVerification.Failed(3, FailureReason.SEQ_GAP, 2), log.verify());
    }

    @Test
    void aRewrittenAndRehashedRowBreaksTheLinkOfTheNextOne() {
        List<AuditEvent> events = appendAll(6);
        AuditEvent e = events.get(2);
        AuditEvent forged = new AuditEvent(e.seq(), e.scheme(), e.entityType(), e.entityId(), e.actor(), e.action(),
                e.beforeState(), "{\"n\":999}", e.occurredAtMicros(), e.previousHash(), e.currentHash());
        String rehash = AuditChain.recompute(forged);
        withGuardsOff(() -> jdbc.update("UPDATE audit_event SET after_state = ?, current_hash = ? WHERE seq = 3",
                "{\"n\":999}", rehash));
        assertEquals(new AuditVerification.Failed(4, FailureReason.CHAIN_BREAK, 3), log.verify());
    }

    @Test
    void cuttingEventsOffTheEndIsNotDetectedWithoutAHeadRecordedElsewhere() {
        // The documented limit (ADR-0004, ADR-0010): a shorter chain is still a valid chain. Its head differs from the
        // one that was anchored outside the database, which is what exposes the cut.
        List<AuditEvent> events = appendAll(6);
        String anchoredHash = events.get(5).currentHash();
        withGuardsOff(() -> jdbc.update("DELETE FROM audit_event WHERE seq = 6"));
        AuditVerification result = log.verify();
        assertTrue(result instanceof AuditVerification.Ok);
        assertNotEquals(anchoredHash, ((AuditVerification.Ok) result).headHash());
        assertEquals(5L, ((AuditVerification.Ok) result).headSeq());
    }

    @Test
    void concurrentAppendsFormOneUnbrokenChainWithNoGapsOrForks() throws Exception {
        int threads = 16;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int worker = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 1; i <= perThread; i++) {
                    append(draft(worker * 1_000 + i));
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        long total = (long) threads * perThread;
        assertEquals(total, count());
        assertEquals(1L, jdbc.queryForObject("SELECT min(seq) FROM audit_event", Long.class));
        assertEquals(total, jdbc.queryForObject("SELECT max(seq) FROM audit_event", Long.class));
        AuditVerification result = log.verify();
        assertTrue(result instanceof AuditVerification.Ok, "chain after concurrent appends: " + result);
        assertEquals(total, ((AuditVerification.Ok) result).count());
        assertEquals(log.head().hash(), ((AuditVerification.Ok) result).headHash());
    }

    private static AuditDraft draft(int n) {
        return new AuditDraft("exception_case", String.valueOf(n), "alice", "CASE_ASSIGNED",
                "{\"n\":" + (n - 1) + "}", "{\"n\":" + n + "}", 1_000L * n);
    }

    private static AuditEvent append(AuditDraft draft) {
        return Objects.requireNonNull(transaction.execute(status -> log.append(draft)));
    }

    private static List<AuditEvent> appendAll(int count) {
        List<AuditEvent> events = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            events.add(append(draft(i)));
        }
        return events;
    }

    private static long count() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class));
    }

    private static AuditEvent raw(long seq, String previousHash, String currentHash) {
        return new AuditEvent(seq, 1, "t", "1", "a", "A", "{}", "{}", 0L, previousHash, currentHash);
    }

    private static void withGuardsOff(Runnable action) {
        jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER USER");
        try {
            action.run();
        } finally {
            jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER USER");
        }
    }

    private static String sqlState(String sql) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private static String tryInsert(AuditEvent e) {
        String sql = "INSERT INTO audit_event (seq, scheme, entity_type, entity_id, actor, action, before_state,"
                + " after_state, occurred_at_micros, previous_hash, current_hash)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, e.seq());
            p.setInt(2, e.scheme());
            p.setString(3, e.entityType());
            p.setString(4, e.entityId());
            p.setString(5, e.actor());
            p.setString(6, e.action());
            p.setString(7, e.beforeState());
            p.setString(8, e.afterState());
            p.setLong(9, e.occurredAtMicros());
            p.setString(10, e.previousHash());
            p.setString(11, e.currentHash());
            p.executeUpdate();
            return null;
        } catch (SQLException ex) {
            return ex.getSQLState();
        }
    }
}