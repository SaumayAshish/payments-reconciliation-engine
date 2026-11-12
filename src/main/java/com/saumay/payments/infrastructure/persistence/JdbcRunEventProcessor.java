package com.saumay.payments.infrastructure.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongConsumer;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies one validated RUN_COMPLETED event.
 *
 * The marker and the case-intake work share the same datasource transaction.
 * Replaying the same event and run is harmless. Reusing an event ID for a
 * different run is rejected rather than silently accepted.
 */
public final class JdbcRunEventProcessor {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final LongConsumer openCases;

    public JdbcRunEventProcessor(DataSource dataSource, LongConsumer openCases) {
        DataSource requiredDataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(requiredDataSource);
        this.transaction = new TransactionTemplate(
                new DataSourceTransactionManager(requiredDataSource));
        this.openCases = Objects.requireNonNull(openCases, "openCases");
    }

    /**
     * @return true if this event was processed, false for a matching replay
     */
    public boolean process(UUID eventId, long runId) {
        Objects.requireNonNull(eventId, "eventId");
        if (runId <= 0) {
            throw new IllegalArgumentException("runId must be positive");
        }

        return Objects.requireNonNull(transaction.execute(status -> {
            int inserted = jdbc.update(
                    "INSERT INTO processed_run_event (event_id, run_id)"
                            + " VALUES (?, ?) ON CONFLICT (event_id) DO NOTHING",
                    eventId, runId);

            if (inserted == 0) {
                List<Long> existing = jdbc.queryForList(
                        "SELECT run_id FROM processed_run_event WHERE event_id = ?",
                        Long.class, eventId);

                if (existing.size() != 1 || existing.get(0).longValue() != runId) {
                    throw new IllegalStateException(
                            "Event ID has already been associated with a different run");
                }
                return false;
            }

            openCases.accept(runId);
            return true;
        }));
    }
}