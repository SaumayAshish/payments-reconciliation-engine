package com.saumay.payments.application;

import java.util.Objects;

/** A case action was refused for a business reason. The message is safe to show to a client. */
public final class CaseRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final CaseErrorCode code;

    public CaseRejectedException(CaseErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public CaseErrorCode code() {
        return code;
    }
}