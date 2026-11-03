package com.saumay.payments.application;

/** Why an approval action was refused. The API layer maps these to HTTP statuses. */
public enum ApprovalErrorCode {
    APPROVAL_NOT_FOUND,
    APPROVAL_PENDING,
    APPROVAL_ALREADY_DECIDED,
    SEPARATION_OF_DUTIES_VIOLATION,
    CONTROLLED_ACTION_NOT_ALLOWED
}