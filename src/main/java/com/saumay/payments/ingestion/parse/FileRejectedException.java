package com.saumay.payments.ingestion.parse;

import java.util.Objects;

/** The file as a whole cannot be ingested. The message describes the problem, never echoes file content. */
public final class FileRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FileErrorCode code;

    public FileRejectedException(FileErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public FileErrorCode code() {
        return code;
    }
}