package com.saumay.payments.ingestion.normalise;

import java.util.Objects;

/** A raw line failed validation. Carries a stable error code; the message names the field, not the data. */
public final class LineValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final LineErrorCode code;

    public LineValidationException(LineErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public LineErrorCode code() {
        return code;
    }
}