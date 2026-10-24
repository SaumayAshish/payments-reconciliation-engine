package com.saumay.payments.application;

import java.util.Objects;

/** A run request or lookup refused for a business reason. The message is safe to show to a client. */
public final class RunRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final RunErrorCode code;

    public RunRejectedException(RunErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public RunErrorCode code() {
        return code;
    }
}