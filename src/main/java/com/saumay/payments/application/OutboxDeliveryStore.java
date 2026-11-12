package com.saumay.payments.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Short-transaction leasing and delivery state changes for outbox events. */
public interface OutboxDeliveryStore {

    List<Claim> claim(Instant now, Duration leaseDuration, int limit);

    boolean markPublished(UUID eventId, UUID leaseToken, Instant publishedAt);

    boolean reschedule(UUID eventId, UUID leaseToken, Instant nextAttemptAt);

    long backlog();

    record Claim(long sequence, UUID eventId, UUID leaseToken, String topic, String messageKey,
            String payload, int attemptCount) {
        public Claim {
            if (sequence < 1 || attemptCount < 1) {
                throw new IllegalArgumentException("invalid sequence or attempt count");
            }
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(leaseToken, "leaseToken");
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(messageKey, "messageKey");
            Objects.requireNonNull(payload, "payload");
        }
    }
}