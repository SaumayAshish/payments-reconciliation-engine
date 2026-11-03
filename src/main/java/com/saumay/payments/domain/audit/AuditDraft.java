package com.saumay.payments.domain.audit;

import java.util.Objects;

/**
 * What a caller wants recorded, before the chain gives it a sequence number and hashes.
 *
 * <p>The states are canonical JSON text (see {@link CanonicalJson}); use {@code "null"} when there is no state,
 * for example no before-state when something is created. The time is supplied by the caller in epoch
 * microseconds: the domain never reads the clock.
 */
public record AuditDraft(
        String entityType,
        String entityId,
        String actor,
        String action,
        String beforeState,
        String afterState,
        long occurredAtMicros) {

    public AuditDraft {
        AuditEvent.requireText(entityType, "entityType");
        AuditEvent.requireText(entityId, "entityId");
        AuditEvent.requireText(actor, "actor");
        AuditEvent.requireText(action, "action");
        Objects.requireNonNull(beforeState, "beforeState");
        Objects.requireNonNull(afterState, "afterState");
        if (occurredAtMicros < 0) {
            throw new IllegalArgumentException("occurredAtMicros must not be negative");
        }
    }
}