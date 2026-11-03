package com.saumay.payments.domain.approval;

/** A decision was attempted on a request that is no longer pending. */
public final class ApprovalAlreadyDecidedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ApprovalAlreadyDecidedException(ApprovalStatus status) {
        super("the approval request is already " + status);
    }
}