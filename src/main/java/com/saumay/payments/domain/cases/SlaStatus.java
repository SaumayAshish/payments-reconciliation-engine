package com.saumay.payments.domain.cases;

/** Where a case stands against its SLA at a given business date. Derived, never stored (ADR-0008). */
public enum SlaStatus {
    WITHIN_SLA,
    AT_RISK,
    BREACHED
}