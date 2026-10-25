package com.saumay.payments.domain.cases;

/** Everything that can appear on a case timeline (FRD-07). The names match the database constraint exactly. */
public enum CaseActivityType {
    CREATED,
    ASSIGNED,
    COMMENT_ADDED,
    STATUS_CHANGED,
    MATCH_ATTEMPTED,
    MATCH_REJECTED,
    ESCALATED,
    APPROVAL_REQUESTED,
    APPROVAL_APPROVED,
    APPROVAL_REJECTED,
    RESOLVED
}