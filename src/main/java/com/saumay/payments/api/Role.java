package com.saumay.payments.api;

/** The roles of the platform (BRD section 7). Full endpoint authorisation arrives in Phase 8. */
public enum Role {
    ADMIN,
    ANALYST,
    TEAM_LEAD,
    CONTROLLER,
    AUDITOR
}