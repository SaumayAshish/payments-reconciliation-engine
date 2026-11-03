package com.saumay.payments.domain.audit;

/**
 * Walks events in sequence order and stops at the first one that does not fit (ADR-0010 point 8).
 *
 * <p>Each event is checked in a fixed order: its sequence number is the expected one ({@code SEQ_GAP}), its
 * previous hash is the hash of the event before ({@code CHAIN_BREAK}), and its stored hash is the hash of its own
 * content ({@code HASH_MISMATCH}; an unsupported scheme counts here because the stored hash cannot be
 * reproduced). Feed it events a page at a time; it keeps no more than the previous head in memory.
 *
 * <p>Not thread safe: one checker per verification run.
 *
 * <p>What it cannot see: events cut off the end of the chain leave a shorter but valid chain, and a rewrite of the
 * whole chain from some point on is internally consistent. Both are caught only by comparing the resulting head
 * with one recorded outside the database.
 */
public final class AuditChainChecker {

    private AuditHead head = AuditChain.GENESIS;
    private long verified;
    private AuditVerification.Failed failure;

    /** Checks the next event; false once the chain is broken here or was already broken before. */
    public boolean accept(AuditEvent event) {
        if (failure != null) {
            return false;
        }
        long expectedSeq = head.seq() + 1;
        if (event.seq() != expectedSeq) {
            return fail(expectedSeq, AuditVerification.FailureReason.SEQ_GAP);
        }
        if (!event.previousHash().equals(head.hash())) {
            return fail(event.seq(), AuditVerification.FailureReason.CHAIN_BREAK);
        }
        if (!AuditChain.supports(event.scheme()) || !AuditChain.recompute(event).equals(event.currentHash())) {
            return fail(event.seq(), AuditVerification.FailureReason.HASH_MISMATCH);
        }
        head = AuditChain.headOf(event);
        verified++;
        return true;
    }

    public AuditVerification result() {
        if (failure != null) {
            return failure;
        }
        return new AuditVerification.Ok(verified, head.seq(), head.hash());
    }

    private boolean fail(long seq, AuditVerification.FailureReason reason) {
        failure = new AuditVerification.Failed(seq, reason, verified);
        return false;
    }
}