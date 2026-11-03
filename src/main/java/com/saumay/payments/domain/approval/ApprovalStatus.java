package com.saumay.payments.domain.approval;

/** PENDING moves once, to APPROVED or REJECTED. Both are final. */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}