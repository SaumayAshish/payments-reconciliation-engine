package com.saumay.payments.application;

import java.util.Objects;

/** An approval action was refused for a business reason. The message is safe to show to a client. */
public final class ApprovalRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ApprovalErrorCode code;

    public ApprovalRejectedException(ApprovalErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ApprovalErrorCode code() {
        return code;
    }
}