package com.saumay.payments.api;

import com.saumay.payments.application.CaseMatchService;
import com.saumay.payments.application.CaseReader;
import com.saumay.payments.application.CaseService;
import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CasePriority;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.CaseTeam;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The exception work queue and case actions (FRD-12). The controller only translates: the rules live in the case
 * services, and every refusal reaches the client as the one {@link ApiError} shape. Until authentication arrives in
 * Phase 8 the acting user comes from the X-Actor header; Phase 8 replaces that header with the authenticated user.
 * A case is resolved by a manual match or a split; write-off and adjustment resolutions need maker-checker (Phase 5).
 */
@RestController
@RequestMapping("/api/v1")
public class ExceptionController {

    static final String ACTOR_HEADER = "X-Actor";
    static final int MAX_LIMIT = 500;

    private static final String BASE = "/api/v1/exceptions";

    private final CaseService cases;
    private final CaseMatchService matches;
    private final CaseReader reader;

    public ExceptionController(CaseService cases, CaseMatchService matches, CaseReader reader) {
        this.cases = cases;
        this.matches = matches;
        this.reader = reader;
    }

    @GetMapping("/exceptions")
    public ExceptionDtos.CasePageDto list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "team", required = false) String team,
            @RequestParam(name = "priority", required = false) String priority,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "owner", required = false) String owner,
            @RequestParam(name = "after", defaultValue = "0") long after,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        if (after < 0) {
            throw invalid("INVALID_PAGE_PARAMETER", "after must not be negative");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw invalid("INVALID_PAGE_PARAMETER", "limit must be between 1 and " + MAX_LIMIT);
        }
        CaseReader.CaseFilter filter = new CaseReader.CaseFilter(parse(CaseStatus.class, "status", status),
                parse(CaseTeam.class, "team", team), parse(CasePriority.class, "priority", priority),
                parse(CaseKind.class, "kind", kind), owner == null || owner.isBlank() ? null : owner.trim());
        return ExceptionDtos.CasePageDto.from(reader.list(filter, after, limit));
    }

    @GetMapping("/exceptions/{id}")
    public ExceptionDtos.CaseDetailDto get(@PathVariable("id") long id) {
        return reader.find(id).map(ExceptionDtos.CaseDetailDto::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND",
                        "No case exists with that id"));
    }

    @PostMapping("/exceptions/{id}/assign")
    public ExceptionDtos.CaseDto assign(@PathVariable("id") long id,
            @RequestHeader(name = ACTOR_HEADER, required = false) String actor,
            @RequestBody ExceptionDtos.AssignRequest body) {
        String who = actor(actor);
        return ExceptionDtos.CaseDto.from(cases.assign(id, version(body.expectedVersion()),
                required(body.owner(), "owner"), who, body.reason()));
    }

    @PostMapping("/exceptions/{id}/investigate")
    public ExceptionDtos.CaseDto investigate(@PathVariable("id") long id,
            @RequestHeader(name = ACTOR_HEADER, required = false) String actor,
            @RequestBody ExceptionDtos.InvestigateRequest body) {
        String who = actor(actor);
        return ExceptionDtos.CaseDto.from(cases.startInvestigation(id, version(body.expectedVersion()), who));
    }

    @PostMapping("/exceptions/{id}/escalate")
    public ExceptionDtos.CaseDto escalate(@PathVariable("id") long id,
            @RequestHeader(name = ACTOR_HEADER, required = false) String actor,
            @RequestBody ExceptionDtos.EscalateRequest body) {
        String who = actor(actor);
        return ExceptionDtos.CaseDto.from(cases.escalate(id, version(body.expectedVersion()), who,
                required(body.reason(), "reason"), false));
    }

    @PostMapping("/exceptions/{id}/comments")
    public ResponseEntity<ExceptionDtos.CommentCreated> comment(@PathVariable("id") long id,
            @RequestHeader(name = ACTOR_HEADER, required = false) String actor,
            @RequestBody ExceptionDtos.CommentRequest body) {
        String who = actor(actor);
        int ordinal = cases.comment(id, who, required(body.body(), "body"));
        return ResponseEntity.created(URI.create(BASE + "/" + id))
                .body(new ExceptionDtos.CommentCreated(id, ordinal));
    }

    @PostMapping("/exceptions/{id}/match")
    public ExceptionDtos.CaseDto match(@PathVariable("id") long id,
            @RequestHeader(name = ACTOR_HEADER, required = false) String actor,
            @RequestBody ExceptionDtos.MatchRequest body) {
        String who = actor(actor);
        matches.resolve(id, version(body.expectedVersion()), who, required(body.reason(), "reason"),
                ids(body.statementSeqs(), "statementSeqs"), ids(body.ledgerSeqs(), "ledgerSeqs"));
        return ExceptionDtos.CaseDto.from(cases.get(id));
    }

    @PostMapping("/exceptions/{id}/unmatch")
    public ResponseEntity<ExceptionDtos.CaseDto> unmatch(@PathVariable("id") long id,
            @RequestHeader(name = ACTOR_HEADER, required = false) String actor,
            @RequestBody ExceptionDtos.UnmatchRequest body) {
        String who = actor(actor);
        return reopened(matches.unmatchCase(id, who, required(body.reason(), "reason")));
    }

    @PostMapping("/reconciliations/runs/{runId}/matches/{ordinal}/unmatch")
    public ResponseEntity<ExceptionDtos.CaseDto> unmatchRunMatch(@PathVariable("runId") long runId,
            @PathVariable("ordinal") int ordinal,
            @RequestHeader(name = ACTOR_HEADER, required = false) String actor,
            @RequestBody ExceptionDtos.UnmatchRequest body) {
        String who = actor(actor);
        return reopened(matches.unmatchRunMatch(runId, ordinal, who, required(body.reason(), "reason")));
    }

    private ResponseEntity<ExceptionDtos.CaseDto> reopened(long newCaseId) {
        return ResponseEntity.created(URI.create(BASE + "/" + newCaseId))
                .body(ExceptionDtos.CaseDto.from(cases.get(newCaseId)));
    }

    private static String actor(String header) {
        if (header == null || header.isBlank()) {
            throw invalid("ACTOR_REQUIRED", "The " + ACTOR_HEADER + " header is required");
        }
        return header.trim();
    }

    private static long version(Long expectedVersion) {
        if (expectedVersion == null) {
            throw invalid("REQUEST_INVALID", "expectedVersion is required");
        }
        return expectedVersion;
    }

    private static String required(String value, String field) {
        if (value == null) {
            throw invalid("REQUEST_INVALID", field + " is required");
        }
        return value;
    }

    private static List<Long> ids(List<Long> values, String field) {
        if (values == null || values.contains(null)) {
            throw invalid("REQUEST_INVALID", field + " must be a list of line numbers");
        }
        return values;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("INVALID_FILTER", name + " is not a known value");
        }
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}