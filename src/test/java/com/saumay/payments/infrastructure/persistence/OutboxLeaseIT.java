package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.application.OutboxDeliveryStore.Claim;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Lease ownership and recovery against real PostgreSQL. */
@Testcontainers
class OutboxLeaseIT {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static JdbcTemplate jdbc;
    private static JdbcOutboxDeliveryStore store;

    @BeforeAll
    static void start() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcOutboxDeliveryStore(dataSource);
    }

    @BeforeEach
    void clearOutbox() {
        jdbc.execute("TRUNCATE outbox_event RESTART IDENTITY");
    }

    @Test
    void expiredLeaseCanBeReclaimedAndTheOldTokenCannotChangeIt() {
        UUID eventId = insertEvent(1, NOW);
        Claim first = store.claim(NOW, Duration.ofSeconds(10), 10).get(0);

        assertTrue(store.claim(NOW.plusSeconds(9), Duration.ofSeconds(10), 10).isEmpty());
        Claim second = store.claim(NOW.plusSeconds(10), Duration.ofSeconds(10), 10).get(0);
        assertEquals(eventId, second.eventId());
        assertTrue(!first.leaseToken().equals(second.leaseToken()));
        assertEquals(2, second.attemptCount());

        assertFalse(store.markPublished(eventId, first.leaseToken(), NOW.plusSeconds(11)));
        assertFalse(store.reschedule(eventId, first.leaseToken(), NOW.plusSeconds(20)));
        assertTrue(store.markPublished(eventId, second.leaseToken(), NOW.plusSeconds(11)));
        assertEquals(0, store.backlog());
        assertFalse(store.markPublished(eventId, second.leaseToken(), NOW.plusSeconds(12)));
    }

    @Test
    void failedDeliveryWaitsUntilItsNextAttempt() {
        insertEvent(1, NOW);
        Claim first = store.claim(NOW, Duration.ofSeconds(10), 10).get(0);

        assertTrue(store.reschedule(first.eventId(), first.leaseToken(), NOW.plusSeconds(30)));
        assertTrue(store.claim(NOW.plusSeconds(29), Duration.ofSeconds(10), 10).isEmpty());
        Claim retry = store.claim(NOW.plusSeconds(30), Duration.ofSeconds(10), 10).get(0);
        assertEquals(first.eventId(), retry.eventId());
        assertEquals(2, retry.attemptCount());
        assertEquals(1, store.backlog());
    }

    @Test
    void concurrentClaimersDoNotReceiveTheSameEvent() throws Exception {
        for (int i = 0; i < 40; i++) {
            insertEvent(i + 1, NOW);
        }
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            java.util.concurrent.Callable<List<Claim>> action = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start timed out");
                }
                return store.claim(NOW, Duration.ofSeconds(30), 40);
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Claim> a = first.get(30, TimeUnit.SECONDS);
            List<Claim> b = second.get(30, TimeUnit.SECONDS);

            var ids = new HashSet<UUID>();
            for (Claim claim : a) {
                assertTrue(ids.add(claim.eventId()));
            }
            for (Claim claim : b) {
                assertTrue(ids.add(claim.eventId()));
            }
            assertEquals(40, ids.size());
            assertEquals(40, a.size() + b.size());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void invalidClaimsAndEventIdentityChangesAreRejected() {
        insertEvent(1, NOW);
        assertThrows(IllegalArgumentException.class, () -> store.claim(NOW, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> store.claim(NOW, Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class, () -> store.claim(NOW, Duration.ofSeconds(1), 501));
        assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE outbox_event SET payload = 'changed'"));
    }

    private static UUID insertEvent(long aggregateId, Instant due) {
        UUID id = UUID.randomUUID();
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(due, ZoneOffset.UTC);
        jdbc.update("INSERT INTO outbox_event"
                        + " (event_id, event_type, schema_version, aggregate_id, topic,"
                        + " message_key, payload, occurred_at, next_attempt_at)"
                        + " VALUES (?, 'RUN_COMPLETED', 1, ?, 'payments.run.completed.v1', ?, '{}', ?, ?)",
                id, String.valueOf(aggregateId), String.valueOf(aggregateId), timestamp, timestamp);
        return id;
    }
}