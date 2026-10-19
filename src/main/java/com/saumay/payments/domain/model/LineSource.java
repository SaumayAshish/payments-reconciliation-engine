package com.saumay.payments.domain.model;

/** Which side of the reconciliation a canonical line came from. */
public enum LineSource {
    STATEMENT,
    LEDGER
}