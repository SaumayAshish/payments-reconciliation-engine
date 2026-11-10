package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.approval.ApprovalThreshold;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ApprovalConfigurationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void versionsAreCompleteImmutableSnapshotsAndEachChangeIsAudited() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            JdbcAuditLog audit = new JdbcAuditLog(dataSource);
            JdbcApprovalConfigurationStore store = new JdbcApprovalConfigurationStore(dataSource, audit);

            assertEquals(0, store.current().version());
            assertEquals(1_000_000L, store.current().threshold().thresholdFor("GBP"));

            var first = store.append(new ApprovalThreshold(1_000_000L, Map.of("GBP", 500L)),
                    "root", Instant.parse("2026-11-01T10:00:00Z"));
            assertEquals(first, store.current());
            assertThrows(RuntimeException.class, () -> jdbc.update(
                    "INSERT INTO config_approval_threshold (config_version, currency, threshold_minor)"
                            + " VALUES (?, 'EUR', 1)", first.version()));
            assertEquals(1_000_000L, store.current().threshold().thresholdFor("EUR"));
            assertEquals(500L, store.current().threshold().thresholdFor("GBP"));
            assertEquals(1_000_000L, store.current().threshold().thresholdFor("USD"));

            var second = store.append(new ApprovalThreshold(1_000_000L, Map.of("USD", 700L)),
                    "root", Instant.parse("2026-11-01T10:01:00Z"));
            assertTrue(second.version() > first.version());
            assertEquals(second, store.current());
            assertEquals(1_000_000L, second.threshold().thresholdFor("GBP"));
            assertEquals(700L, second.threshold().thresholdFor("USD"));

            assertEquals(2, jdbc.queryForObject(
                    "SELECT count(*) FROM audit_event WHERE action = 'CONFIG_CHANGED'", Integer.class));
            assertThrows(RuntimeException.class, () -> jdbc.update(
                    "UPDATE config_version SET actor = 'other' WHERE version = ?", first.version()));
            assertThrows(RuntimeException.class, () -> jdbc.update(
                    "DELETE FROM config_approval_threshold WHERE config_version = ?", first.version()));
            assertThrows(RuntimeException.class, () -> jdbc.execute("TRUNCATE config_approval_threshold"));
            assertTrue(audit.verify() instanceof com.saumay.payments.domain.audit.AuditVerification.Ok);
        }
    }

    @Test
    void concurrentConfigurationUpdatesAreSealedAndAuditedInVersionOrder() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            JdbcAuditLog audit = new JdbcAuditLog(dataSource);
            JdbcApprovalConfigurationStore store = new JdbcApprovalConfigurationStore(dataSource, audit);
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);

            try {
                var first = executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start timed out");
                    }
                    return store.append(new ApprovalThreshold(1_000_000L, Map.of("GBP", 101L)),
                            "root", Instant.parse("2026-11-01T10:02:00Z"));
                });
                var second = executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start timed out");
                    }
                    return store.append(new ApprovalThreshold(1_000_000L, Map.of("USD", 202L)),
                            "root", Instant.parse("2026-11-01T10:03:00Z"));
                });

                assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
                start.countDown();
                var a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
                var b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(a.version() != b.version());

                long earlier = Math.min(a.version(), b.version());
                long later = Math.max(a.version(), b.version());
                assertEquals(later, store.current().version());
                assertEquals(2, jdbc.queryForObject(
                        "SELECT count(*) FROM config_version_seal WHERE config_version IN (?, ?)",
                        Integer.class, earlier, later));

                var audited = jdbc.queryForList(
                        "SELECT entity_id FROM audit_event WHERE entity_type = 'configuration'"
                                + " AND action = 'CONFIG_CHANGED' AND entity_id IN (?, ?) ORDER BY seq",
                        String.class, String.valueOf(earlier), String.valueOf(later));
                assertEquals(java.util.List.of(String.valueOf(earlier), String.valueOf(later)), audited);
                assertTrue(audit.verify() instanceof com.saumay.payments.domain.audit.AuditVerification.Ok);
            } finally {
                start.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void changingTheBuiltInDefaultIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ApprovalThreshold(-1, Map.of()));
    }
}