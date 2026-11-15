package com.saumay.payments.api;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Produces the API error shape for failures raised by the security filters,
 * before a request reaches an MVC controller.
 */
public final class ApiSecurityErrorHandler
        implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public ApiSecurityErrorHandler(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {

        response.setHeader("WWW-Authenticate", "Bearer");
        write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "A valid authenticated identity is required");
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception) throws IOException, ServletException {

        write(request, response, HttpServletResponse.SC_FORBIDDEN,
                "ACCESS_DENIED",
                "Your role is not allowed to do this");
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message) throws IOException {

        Object attribute = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        String correlationId = attribute instanceof String value
                ? value
                : "unavailable";

        ApiError error = new ApiError(
                code,
                message,
                correlationId,
                clock.instant().toString(),
                List.of());

        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");

        mapper.writeValue(response.getOutputStream(), error);
    }
}