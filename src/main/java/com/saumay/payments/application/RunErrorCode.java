package com.saumay.payments.application;

/** Stable reasons a run request is refused. The API layer maps each one to an HTTP status and public code. */
public enum RunErrorCode {
    UNKNOWN_INGESTION,
    NOT_A_STATEMENT_INGESTION,
    RUN_NOT_FOUND,
    RUN_NOT_COMPLETED,
    REPLAY_INPUTS_UNAVAILABLE
}