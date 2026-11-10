package com.saumay.payments.application;

import com.saumay.payments.domain.audit.AuditDraft;
import com.saumay.payments.domain.audit.AuditEvent;
import com.saumay.payments.domain.audit.AuditHead;
import com.saumay.payments.domain.audit.AuditVerification;

/**
 * The tamper-evident record of what happened (ADR-0004, ADR-0010). Writers append; nobody edits or removes.
 */
public interface AuditLog {

    /**
     * Appends the next event of the chain. It must be called inside the transaction of the action it records, so the
     * action and its evidence commit or roll back together; it refuses to run outside one. Call it last in the
     * transaction: it takes the chain lock and holds it until commit, so taking every other lock first keeps the
     * lock order the same everywhere.
     */
    AuditEvent append(AuditDraft draft);

    /** The current end of the chain, or the genesis head when it is empty. */
    AuditHead head();

    /** Walks the whole chain and reports it intact or names the first position that is not. */
    AuditVerification verify();

    /** Verifies the chain and checks that the supplied historical anchor belongs to it. */
    default AuditVerification verify(AuditHead expected) {
        throw new UnsupportedOperationException("Historical anchor verification is not implemented");
    }
}