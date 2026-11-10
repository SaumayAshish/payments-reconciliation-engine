package com.saumay.payments.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Measures selected request durations without putting identifiers in metric tags. */
@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10)
public final class OperationalTimingFilter extends OncePerRequestFilter {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(OperationalTimingFilter.class);

    private final MeterRegistry meters;

    public OperationalTimingFilter(MeterRegistry meters) {
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String operation = operation(request);
        if (operation == null) {
            chain.doFilter(request, response);
            return;
        }

        Timer.Sample sample = Timer.start(meters);
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            String outcome = completed && response.getStatus() < 400 ? "success" : "failure";
            LOGGER.info("operational.request operation={} outcome={}", operation, outcome);
            if ("reconciliation".equals(operation)) {
                sample.stop(meters.timer("reconciliation_request_duration", "outcome", outcome));
            } else {
                sample.stop(meters.timer("ingestion_request_duration",
                        "kind", operation, "outcome", outcome));
            }
        }
    }

    private static String operation(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return null;
        }
        return switch (request.getRequestURI()) {
            case "/api/v1/ingestions/statements" -> "statement";
            case "/api/v1/ingestions/ledger" -> "ledger";
            case "/api/v1/reconciliations/runs" -> "reconciliation";
            default -> null;
        };
    }
}