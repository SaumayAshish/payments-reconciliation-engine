package com.saumay.payments.api;

import com.saumay.payments.application.AuditLog;
import com.saumay.payments.application.AuditReader;
import com.saumay.payments.domain.audit.AuditEvent;
import com.saumay.payments.domain.audit.AuditVerification;
import java.util.EnumSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes audit-chain verification to authorised audit operators. */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private static final Set<Role> READERS = EnumSet.of(Role.AUDITOR, Role.ADMIN);

    private final AuditLog audit;
    private final AuditReader reader;
    private final ActorContext actors;

    public AuditController(AuditLog audit, AuditReader reader, ActorContext actors) {
        this.audit = audit;
        this.reader = reader;
        this.actors = actors;
    }

    @GetMapping("/{entityType}/{entityId}")
    public Map<String, List<AuditEvent>> history(
            @PathVariable("entityType") String entityType,
            @PathVariable("entityId") String entityId,
            @RequestParam(name = "afterSeq", defaultValue = "0") long afterSeq,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestHeader(name = ExceptionController.ACTOR_HEADER, required = false) String header) {
        actors.resolve(header).require(READERS);
        if (afterSeq < 0 || limit < 1 || limit > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_INVALID",
                    "afterSeq must be non-negative and limit must be between 1 and 500");
        }
        return Map.of("events", reader.findByEntity(entityType, entityId, afterSeq, limit));
    }

    @GetMapping("/verify")
    public Map<String, Object> verify(
            @RequestHeader(name = ExceptionController.ACTOR_HEADER, required = false) String header,
            @RequestParam(name = "expectedSeq", required = false) String expectedSeq,
            @RequestParam(name = "expectedHash", required = false) String expectedHash) {
        actors.resolve(header).require(READERS);
        AuditVerification result;
        if (expectedSeq == null && expectedHash == null) {
            result = audit.verify();
        } else {
            com.saumay.payments.domain.audit.AuditHead expected;
            try {
                if (expectedSeq == null || expectedHash == null) {
                    throw new IllegalArgumentException("both anchor fields are required");
                }
                expected = new com.saumay.payments.domain.audit.AuditHead(
                        Long.parseLong(expectedSeq), expectedHash);
            } catch (IllegalArgumentException invalid) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_INVALID",
                        "expectedSeq and expectedHash must form a valid audit anchor");
            }
            result = audit.verify(expected);
        }
        if (result instanceof AuditVerification.Ok ok) {
            return Map.of("status", "OK", "count", ok.count(), "headSeq", ok.headSeq(),
                    "headHash", ok.headHash());
        }
        if (result instanceof AuditVerification.AnchorMismatch mismatch) {
            return Map.of("status", "FAILED", "reason", mismatch.reason(),
                    "expectedSeq", mismatch.expected().seq(), "expectedHash", mismatch.expected().hash(),
                    "headSeq", mismatch.actual().seq(), "headHash", mismatch.actual().hash());
        }
        AuditVerification.Failed failed = (AuditVerification.Failed) result;
        return Map.of("status", "FAILED", "firstBadSeq", failed.firstBadSeq(),
                "reason", failed.reason().name(), "verifiedCount", failed.verifiedCount());
    }
}