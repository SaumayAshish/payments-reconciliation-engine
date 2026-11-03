package com.saumay.payments.domain.audit;

import java.util.Objects;

/** The last event of the chain: its sequence number and hash. The empty chain's head is {@link AuditChain#GENESIS}. */
public record AuditHead(long seq, String hash) {

    public AuditHead {
        if (seq < 0) {
            throw new IllegalArgumentException("seq must not be negative");
        }
        Objects.requireNonNull(hash, "hash");
        if (!AuditEvent.isHash(hash)) {
            throw new IllegalArgumentException("hash must be 64 lower-case hex characters");
        }
    }
}