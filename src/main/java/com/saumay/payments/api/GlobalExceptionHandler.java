package com.saumay.payments.api;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into the single {@link ApiError} shape. Spring's own MVC errors (405, 415, bad JSON and so on)
 * are routed through the same shape. Unexpected errors are logged in full with their correlation id and reported to
 * the client generically, so no stack trace, SQL or credential ever leaves the server.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
        return respond(ex.status(), ex.code(), ex.getMessage(), List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        LOG.error("Unhandled exception, correlationId={}", correlationId(request), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", List.of(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiError.FieldProblem> problems = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField)
                        .thenComparing(error -> String.valueOf(error.getDefaultMessage())))
                .map(error -> new ApiError.FieldProblem(error.getField(), String.valueOf(error.getDefaultMessage())))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", problems, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body is missing or malformed", List.of(), request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        String message = statusCode instanceof HttpStatus known ? known.getReasonPhrase() : "Request rejected";
        return respond(statusCode, codeFor(statusCode), message, List.of(), request);
    }

    private ResponseEntity<Object> respond(HttpStatusCode status, String code, String message,
                                           List<ApiError.FieldProblem> details, WebRequest request) {
        ApiError error = new ApiError(code, message, correlationId(request), clock.instant().toString(), details);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(error);
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "BAD_REQUEST";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is4xxClientError() ? "REQUEST_REJECTED" : "INTERNAL_ERROR";
        };
    }

    private static String correlationId(WebRequest request) {
        Object id = request.getAttribute(CorrelationIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return id == null ? "unavailable" : id.toString();
    }
}