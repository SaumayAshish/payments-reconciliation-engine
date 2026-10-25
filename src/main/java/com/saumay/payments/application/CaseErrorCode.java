package com.saumay.payments.application;

/** Why a case action was refused. The API layer maps these to HTTP statuses; the application layer knows none. */
public enum CaseErrorCode {
    CASE_NOT_FOUND,
    CASE_VERSION_CONFLICT,
    ILLEGAL_STATE_TRANSITION,
    CASE_OWNER_REQUIRED,
    CASE_REQUEST_INVALID
}