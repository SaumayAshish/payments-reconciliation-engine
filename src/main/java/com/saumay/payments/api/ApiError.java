package com.saumay.payments.api;

import java.util.List;

/**
 * The one error shape every API failure uses. The timestamp is an ISO-8601 string so its format does not depend on
 * JSON mapper configuration. Nothing here carries stack traces, SQL or exception class names.
 */
public record ApiError(String code, String message, String correlationId, String timestamp,
                       List<FieldProblem> details) {

    /** One invalid field in a rejected request. */
    public record FieldProblem(String field, String message) {
    }

    public ApiError {
        details = List.copyOf(details);
    }
}