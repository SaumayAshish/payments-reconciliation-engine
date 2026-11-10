package com.saumay.payments.api;

import com.saumay.payments.application.CaseService;
import com.saumay.payments.application.ControlledResolutionService;
import com.saumay.payments.domain.approval.ControlledAction;
import java.net.URI;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Write-offs, adjustments and their approval (FRD-08). Resolve answers 200 with the resolved case when the amount is
 * at or below the threshold, and 202 with the pending approval above it. The acting user comes from the
 * {@link ActorContext}; the controller only translates.
 */
@RestController
@RequestMapping("/api/v1")
public class ControlledResolutionController {

    private static final Set<Role> MAKERS = EnumSet.of(Role.ANALYST, Role.TEAM_LEAD, Role.CONTROLLER);
    private static final Set<Role> CHECKERS = EnumSet.of(Role.CONTROLLER);
    private static final Set<Role> READERS = EnumSet.of(Role.ANALYST, Role.TEAM_LEAD, Role.CONTROLLER, Role.AUDITOR,
            Role.ADMIN);

    private final ControlledResolutionService service;
    private final CaseService cases;
    private final ActorContext actors;

    public ControlledResolutionController(ControlledResolutionService service, CaseService cases,
            ActorContext actors) {
        this.service = service;
        this.cases = cases;
        this.actors = actors;
    }

    @PostMapping("/exceptions/{id}/resolve")
    public ResponseEntity<Object> resolve(@PathVariable("id") long id,
            @RequestHeader(name = ExceptionController.ACTOR_HEADER, required = false) String header,
            @RequestBody ApprovalDtos.ResolveRequest body) {
        Actor actor = actors.resolve(header);
        actor.require(MAKERS);
        ControlledResolutionService.Outcome outcome = service.resolve(id, version(body.expectedVersion()), actor.id(),
                action(body.action()), required(body.reason(), "reason"));
        if (outcome.pendingApproval()) {
            return ResponseEntity.accepted().location(URI.create("/api/v1/approvals/" + outcome.approvalId()))
                    .body(ApprovalDtos.ApprovalDto.from(service.get(outcome.approvalId())));
        }
        return ResponseEntity.ok(ExceptionDtos.CaseDto.from(cases.get(id)));
    }

    @GetMapping("/approvals/{id}")
    public ApprovalDtos.ApprovalDto get(@PathVariable("id") long id,
            @RequestHeader(name = ExceptionController.ACTOR_HEADER, required = false) String header) {
        actors.resolve(header).require(READERS);
        return ApprovalDtos.ApprovalDto.from(service.get(id));
    }

    @PostMapping("/approvals/{id}/approve")
    public ApprovalDtos.ApprovalDto approve(@PathVariable("id") long id,
            @RequestHeader(name = ExceptionController.ACTOR_HEADER, required = false) String header,
            @RequestBody(required = false) ApprovalDtos.DecisionRequest body) {
        Actor actor = actors.resolve(header);
        actor.require(CHECKERS);
        return ApprovalDtos.ApprovalDto.from(service.approve(id, actor.id(), body == null ? null : body.note()));
    }

    @PostMapping("/approvals/{id}/reject")
    public ApprovalDtos.ApprovalDto reject(@PathVariable("id") long id,
            @RequestHeader(name = ExceptionController.ACTOR_HEADER, required = false) String header,
            @RequestBody ApprovalDtos.DecisionRequest body) {
        Actor actor = actors.resolve(header);
        actor.require(CHECKERS);
        return ApprovalDtos.ApprovalDto.from(service.reject(id, actor.id(), required(body.note(), "note")));
    }

    private static ControlledAction action(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("action is required");
        }
        try {
            return ControlledAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("action must be WRITE_OFF or ADJUSTMENT");
        }
    }

    private static long version(Long expectedVersion) {
        if (expectedVersion == null) {
            throw invalid("expectedVersion is required");
        }
        return expectedVersion;
    }

    private static String required(String value, String field) {
        if (value == null) {
            throw invalid(field + " is required");
        }
        return value;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", message);
    }
}