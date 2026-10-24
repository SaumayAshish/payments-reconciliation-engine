package com.saumay.payments.application;

/** Where a reconciliation run is in its life (FRD-10). A run only moves forward and never leaves a final state. */
public enum RunStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED
}