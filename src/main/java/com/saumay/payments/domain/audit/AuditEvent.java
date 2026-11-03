package com.saumay.payments.domain.audit;

import java.util.Objects;

/**
 * One link of the audit chain, exactly as stored (ADR-0004, ADR-0010).
 *
 * <p>The constructor checks shape only, never that {@code currentHash} is correct: the verifier must be able to
 * hold a tampered event in order to report it. Hash correctness is {@link AuditChain#recompute(AuditEvent)}.
 */
public record AuditEvent(
        long seq,
        int scheme,
        String entityType,
        String entityId,
        String actor,
        String action,
        String beforeState,
        String afterState,
        long occurredAtMicros,
        String previousHash,
        String currentHash) {

    public AuditEvent {
        if (seq < 1) {
            throw new IllegalArgumentException("seq starts at 1");
        }
        if (scheme < 1) {
            throw new IllegalArgumentException("scheme starts at 1");
        }
        requireText(entityType, "entityType");
        requireText(entityId, "entityId");
        requireText(actor, "actor");
        requireText(action, "action");
        Objects.requireNonNull(beforeState, "beforeState");
        Objects.requireNonNull(afterState, "afterState");
        if (occurredAtMicros < 0) {
            throw new IllegalArgumentException("occurredAtMicros must not be negative");
        }
        Objects.requireNonNull(previousHash, "previousHash");
        Objects.requireNonNull(currentHash, "currentHash");
        if (!isHash(previousHash) || !isHash(currentHash)) {
            throw new IllegalArgumentException("hashes must be 64 lower-case hex characters");
        }
    }

    static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    static boolean isHash(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean lowerHex = c >= 'a' && c <= 'f';
            if (!digit && !lowerHex) {
                return false;
            }
        }
        return true;
    }
}