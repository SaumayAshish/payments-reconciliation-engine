package com.saumay.payments.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Publishes one leased event per invocation, with bounded retry backoff. */
public final class OutboxPublisher {

    private static final Duration LEASE = Duration.ofSeconds(60);

    private final OutboxDeliveryStore store;
    private final EventTransport transport;
    private final Clock clock;

    public OutboxPublisher(OutboxDeliveryStore store, EventTransport transport, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public enum Outcome {
        EMPTY,
        PUBLISHED,
        RETRY_SCHEDULED,
        LEASE_LOST,
        INTERRUPTED
    }

    public Outcome publishOne() {
        List<OutboxDeliveryStore.Claim> claims = store.claim(clock.instant(), LEASE, 1);
        if (claims.isEmpty()) {
            return Outcome.EMPTY;
        }
        OutboxDeliveryStore.Claim claim = claims.get(0);

        try {
            transport.send(claim.topic(), claim.messageKey(), claim.payload());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.INTERRUPTED;
        } catch (RuntimeException failed) {
            long delay = Math.min(300L, 5L << Math.min(claim.attemptCount() - 1, 6));
            return store.reschedule(claim.eventId(), claim.leaseToken(),
                    clock.instant().plusSeconds(delay)) ? Outcome.RETRY_SCHEDULED : Outcome.LEASE_LOST;
        }

        return store.markPublished(claim.eventId(), claim.leaseToken(), clock.instant())
                ? Outcome.PUBLISHED : Outcome.LEASE_LOST;
    }
}