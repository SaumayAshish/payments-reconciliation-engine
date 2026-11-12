package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.OutboxDeliveryStore;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL lease operations; no broker call occurs inside a transaction. */
public final class JdbcOutboxDeliveryStore implements OutboxDeliveryStore {

    private static final int MAX_BATCH = 500;

    private static final String CLAIM =
            "WITH eligible AS ("
                    + " SELECT seq FROM outbox_event"
                    + " WHERE (status = 'PENDING' AND next_attempt_at <= ?)"
                    + " OR (status = 'IN_FLIGHT' AND lease_until <= ?)"
                    + " ORDER BY seq LIMIT ? FOR UPDATE SKIP LOCKED"
                    + ") UPDATE outbox_event o"
                    + " SET status = 'IN_FLIGHT', lease_token = ?, lease_until = ?,"
                    + " attempt_count = attempt_count + 1"
                    + " FROM eligible e WHERE o.seq = e.seq"
                    + " RETURNING o.seq, o.event_id, o.lease_token, o.topic, o.message_key,"
                    + " o.payload, o.attempt_count";

    private static final String PUBLISH =
            "UPDATE outbox_event SET status = 'PUBLISHED', published_at = ?,"
                    + " lease_token = NULL, lease_until = NULL"
                    + " WHERE event_id = ? AND status = 'IN_FLIGHT' AND lease_token = ?";

    private static final String RETRY =
            "UPDATE outbox_event SET status = 'PENDING', next_attempt_at = ?,"
                    + " lease_token = NULL, lease_until = NULL"
                    + " WHERE event_id = ? AND status = 'IN_FLIGHT' AND lease_token = ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcOutboxDeliveryStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public List<Claim> claim(Instant now, Duration leaseDuration, int limit) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (limit < 1 || limit > MAX_BATCH) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH);
        }
        UUID token = UUID.randomUUID();
        OffsetDateTime current = utc(now);
        OffsetDateTime expires = utc(now.plus(leaseDuration));

        List<Claim> claimed = Objects.requireNonNull(transaction.execute(status -> jdbc.query(
                CLAIM,
                (rs, rowNum) -> new Claim(rs.getLong("seq"),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("lease_token", UUID.class),
                        rs.getString("topic"), rs.getString("message_key"),
                        rs.getString("payload"), rs.getInt("attempt_count")),
                current, current, limit, token, expires)));
        return claimed.stream().sorted(Comparator.comparingLong(Claim::sequence)).toList();
    }

    @Override
    public boolean markPublished(UUID eventId, UUID leaseToken, Instant publishedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        Objects.requireNonNull(publishedAt, "publishedAt");
        return jdbc.update(PUBLISH, utc(publishedAt), eventId, leaseToken) == 1;
    }

    @Override
    public boolean reschedule(UUID eventId, UUID leaseToken, Instant nextAttemptAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        return jdbc.update(RETRY, utc(nextAttemptAt), eventId, leaseToken) == 1;
    }

    @Override
    public long backlog() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE status <> 'PUBLISHED'", Long.class));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}