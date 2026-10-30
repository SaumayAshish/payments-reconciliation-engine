package com.saumay.payments.api;

import com.saumay.payments.application.ReconciliationRunService;
import com.saumay.payments.application.RunHeader;
import com.saumay.payments.application.RunRejectedException;
import com.saumay.payments.application.RunRequest;
import com.saumay.payments.application.RunResultReader;
import com.saumay.payments.matching.MatchingConfig;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Start, read, page through and replay reconciliation runs (FRD-10). The controller only translates: the rules live
 * in {@link ReconciliationRunService}, and every refusal reaches the client as the one {@link ApiError} shape.
 */
@RestController
@RequestMapping("/api/v1/reconciliations/runs")
public class ReconciliationController {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private static final String BASE = "/api/v1/reconciliations/runs";

    private final ReconciliationRunService service;
    private final RunResultReader results;

    public ReconciliationController(ReconciliationRunService service, RunResultReader results) {
        this.service = service;
        this.results = results;
    }

    @PostMapping
    public ResponseEntity<ReconciliationDtos.RunDetail> start(@RequestBody ReconciliationDtos.StartRequest body) {
        RunRequest request = toRequest(body);
        RunHeader run;
        try {
            run = service.start(request);
        } catch (RunRejectedException e) {
            throw translate(e);
        }
        return ResponseEntity.created(URI.create(BASE + "/" + run.id())).body(ReconciliationDtos.RunDetail.from(run));
    }

    @GetMapping("/{id}")
    public ReconciliationDtos.RunDetail get(@PathVariable("id") long id) {
        try {
            return ReconciliationDtos.RunDetail.from(service.get(id));
        } catch (RunRejectedException e) {
            throw translate(e);
        }
    }

    @GetMapping("/{id}/results")
    public ReconciliationDtos.Page<?> results(@PathVariable("id") long id,
            @RequestParam(name = "view", defaultValue = "matches") String view,
            @RequestParam(name = "after", defaultValue = "0") long after,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        if (after < 0) {
            throw invalid("INVALID_PAGE_PARAMETER", "after must not be negative");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw invalid("INVALID_PAGE_PARAMETER", "limit must be between 1 and " + MAX_LIMIT);
        }
        try {
            service.requireCompleted(id);
        } catch (RunRejectedException e) {
            throw translate(e);
        }
        return switch (view) {
            case "matches" -> matches(id, ordinal(after), limit);
            case "exceptions" -> exceptions(id, ordinal(after), limit);
            case "carried-forward" -> carriedForward(id, after, limit);
            case "notes" -> new ReconciliationDtos.Page<>(id, view, results.notes(id), null);
            default -> throw invalid("INVALID_RESULT_VIEW",
                    "view must be one of matches, exceptions, carried-forward, notes");
        };
    }

    @PostMapping("/{id}/replay")
    public ReconciliationDtos.ReplayDto replay(@PathVariable("id") long id) {
        try {
            return ReconciliationDtos.ReplayDto.from(service.replay(id));
        } catch (RunRejectedException e) {
            throw translate(e);
        }
    }

    private ReconciliationDtos.Page<ReconciliationDtos.MatchDto> matches(long id, int after, int limit) {
        List<RunResultReader.MatchView> rows = results.matches(id, after, limit);
        Long next = rows.size() == limit ? (long) rows.get(rows.size() - 1).ordinal() : null;
        return new ReconciliationDtos.Page<>(id, "matches",
                rows.stream().map(ReconciliationDtos.MatchDto::from).toList(), next);
    }

    private ReconciliationDtos.Page<ReconciliationDtos.ExceptionDto> exceptions(long id, int after, int limit) {
        List<RunResultReader.ExceptionView> rows = results.exceptions(id, after, limit);
        Long next = rows.size() == limit ? (long) rows.get(rows.size() - 1).ordinal() : null;
        return new ReconciliationDtos.Page<>(id, "exceptions",
                rows.stream().map(ReconciliationDtos.ExceptionDto::from).toList(), next);
    }

    private ReconciliationDtos.Page<Long> carriedForward(long id, long after, int limit) {
        List<Long> rows = results.carriedForward(id, after, limit);
        Long next = rows.size() == limit ? rows.get(rows.size() - 1) : null;
        return new ReconciliationDtos.Page<>(id, "carried-forward", rows, next);
    }

    private static int ordinal(long after) {
        if (after > Integer.MAX_VALUE) {
            throw invalid("INVALID_PAGE_PARAMETER", "after is beyond the last row");
        }
        return (int) after;
    }

    private static RunRequest toRequest(ReconciliationDtos.StartRequest body) {
        if (body.businessDate() == null) {
            throw invalid("RUN_REQUEST_INVALID", "businessDate is required");
        }
        if (body.statementIngestionIds() == null || body.statementIngestionIds().stream().anyMatch(i -> i == null)) {
            throw invalid("RUN_REQUEST_INVALID", "statementIngestionIds is required and must list ids");
        }
        try {
            return new RunRequest(body.businessDate(), body.statementIngestionIds(), toConfig(body.config()));
        } catch (IllegalArgumentException e) {
            throw invalid("RUN_REQUEST_INVALID", e.getMessage());
        }
    }

    private static MatchingConfig toConfig(ReconciliationDtos.ConfigOverride o) {
        MatchingConfig d = MatchingConfig.defaults();
        if (o == null) {
            return d;
        }
        return new MatchingConfig(
                or(o.autoThresholdMilli(), d.autoThresholdMilli()),
                or(o.reviewThresholdMilli(), d.reviewThresholdMilli()),
                or(o.dateWindowBusinessDays(), d.dateWindowBusinessDays()),
                or(o.lookbackBusinessDays(), d.lookbackBusinessDays()),
                or(o.maxOneToManyCandidates(), d.maxOneToManyCandidates()),
                or(o.fuzzyMinSimilarityMilli(), d.fuzzyMinSimilarityMilli()),
                or(o.fuzzyCounterpartyWeightMilli(), d.fuzzyCounterpartyWeightMilli()));
    }

    private static int or(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    static ApiException translate(RunRejectedException e) {
        return switch (e.code()) {
            case RUN_NOT_FOUND -> new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", e.getMessage());
            case RUN_NOT_COMPLETED -> new ApiException(HttpStatus.CONFLICT, "RUN_NOT_COMPLETED", e.getMessage());
            case REPLAY_INPUTS_UNAVAILABLE ->
                    new ApiException(HttpStatus.CONFLICT, "RUN_REPLAY_INPUTS_UNAVAILABLE", e.getMessage());
            case UNKNOWN_INGESTION ->
                    new ApiException(HttpStatus.valueOf(422), "RUN_UNKNOWN_INGESTION", e.getMessage());
            case NOT_A_STATEMENT_INGESTION -> new ApiException(HttpStatus.valueOf(422),
                    "RUN_NOT_A_STATEMENT_INGESTION", e.getMessage());
        };
    }
}