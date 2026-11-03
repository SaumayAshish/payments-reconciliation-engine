package com.saumay.payments.domain.audit;

/** The outcome of walking the chain (ADR-0010 point 8): intact, or the first position where it is not. */
public sealed interface AuditVerification permits AuditVerification.Ok, AuditVerification.Failed {

    enum FailureReason {
        /** The event's stored hash is not the hash of its own content: the row was edited. */
        HASH_MISMATCH,
        /** The event's previous hash is not the hash of the event before it: a link was cut or rewritten. */
        CHAIN_BREAK,
        /** A sequence number is missing, repeated or out of order: an event was deleted, duplicated or moved. */
        SEQ_GAP
    }

    /** Every event checked out. {@code headSeq} and {@code headHash} are the chain's head, worth anchoring elsewhere. */
    record Ok(long count, long headSeq, String headHash) implements AuditVerification {
    }

    /**
     * The chain is broken. {@code firstBadSeq} is the first position that fails; for {@code SEQ_GAP} it is the
     * sequence number that was expected and not found. {@code verifiedCount} events before it were fine.
     */
    record Failed(long firstBadSeq, FailureReason reason, long verifiedCount) implements AuditVerification {
    }
}