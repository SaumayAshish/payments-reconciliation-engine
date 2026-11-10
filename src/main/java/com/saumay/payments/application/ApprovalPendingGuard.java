package com.saumay.payments.application;

import java.util.Objects;

/** The guard backed by the approval store. */
public final class ApprovalPendingGuard implements PendingApprovalGuard {

    private final ApprovalStore approvals;

    public ApprovalPendingGuard(ApprovalStore approvals) {
        this.approvals = Objects.requireNonNull(approvals, "approvals");
    }

    @Override
    public void requireNone(long caseId) {
        if (approvals.findPendingForCase(caseId).isPresent()) {
            throw new ApprovalRejectedException(ApprovalErrorCode.APPROVAL_PENDING,
                    "Case " + caseId + " has a pending approval request; decide it first");
        }
    }
}