package com.saumay.payments.application;

/** Refuses a case action while the case has an approval request waiting for a decision (ADR-0011). */
public interface PendingApprovalGuard {

    void requireNone(long caseId);

    /** A guard that never refuses, for services built without approvals. */
    static PendingApprovalGuard none() {
        return caseId -> {
        };
    }
}