package com.saumay.payments.api;

import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * A failure the API layer reports deliberately, with a stable machine-readable code such as
 * RECONCILIATION_DUPLICATE_FILE. The message must be safe to show to a client.
 */
public final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = Objects.requireNonNull(status, "status");
        this.code = Objects.requireNonNull(code, "code");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}