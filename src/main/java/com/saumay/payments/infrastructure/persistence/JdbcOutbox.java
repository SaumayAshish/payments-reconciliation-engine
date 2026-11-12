package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.domain.audit.CanonicalJson;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Writes event evidence inside the transaction of the business change. */
final class JdbcOutbox {

    private static final String INSERT =
            "INSERT INTO outbox_event (event_id, event_type, schema_version, aggregate_id,"
                    + " topic, message_key, payload, occurred_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private JdbcOutbox() {
    }

    static void caseCreated(JdbcTemplate jdbc, long caseId, String origin, Instant occurredAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An outbox event requires an active transaction");
        }
        UUID eventId = UUID.randomUUID();
        String id = String.valueOf(caseId);
        String payload = CanonicalJson.write(Map.of(
                "eventId", eventId.toString(),
                "eventType", "CASE_CREATED",
                "schemaVersion", 1,
                "caseId", caseId,
                "origin", origin,
                "occurredAt", occurredAt.toString()));
        jdbc.update(INSERT, eventId, "CASE_CREATED", 1, id,
                "payments.exception.created.v1", id, payload,
                OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
    }

    static void runCompleted(JdbcTemplate jdbc, long runId, Instant occurredAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An outbox event requires an active transaction");
        }
        UUID eventId = UUID.randomUUID();
        String id = String.valueOf(runId);
        String payload = CanonicalJson.write(Map.of(
                "eventId", eventId.toString(),
                "eventType", "RUN_COMPLETED",
                "schemaVersion", 1,
                "runId", runId,
                "occurredAt", occurredAt.toString()));
        jdbc.update(INSERT, eventId, "RUN_COMPLETED", 1, id,
                "payments.run.completed.v1", id, payload,
                OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
    }
}