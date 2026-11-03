package com.saumay.payments.application;

import com.saumay.payments.domain.approval.ApprovalRequest;
import com.saumay.payments.domain.approval.ApprovalStatus;
import com.saumay.payments.domain.audit.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongFunction;

/** Builds the audit notes of approval actions (ADR-0010, ADR-0011). One place decides what describes a request. */
final class ApprovalAudit {

    private ApprovalAudit() {
    }

    /** A new request: nothing before, the pending request after, once the store knows its id. */
    static LongFunction<AuditNote> requested(ApprovalRequest request) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", "NONE");
        String beforeJson = CanonicalJson.write(before);
        return id -> new AuditNote("APPROVAL_REQUESTED", beforeJson, CanonicalJson.write(state(id, request, null)));
    }

    static AuditNote decided(ApprovalRequest before, ApprovalRequest after) {
        String action = after.status() == ApprovalStatus.APPROVED ? "APPROVAL_APPROVED" : "APPROVAL_REJECTED";
        return new AuditNote(action, CanonicalJson.write(state(before.id(), before, null)),
                CanonicalJson.write(state(after.id(), after, null)));
    }

    /** The maker tried to decide their own request: the request is unchanged, the attempt is the evidence. */
    static AuditNote denied(ApprovalRequest request, String attemptedBy) {
        String state = CanonicalJson.write(state(request.id(), request, null));
        return new AuditNote("APPROVAL_DENIED", state, CanonicalJson.write(state(request.id(), request, attemptedBy)));
    }

    private static Map<String, Object> state(Long id, ApprovalRequest r, String deniedBy) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", id);
        state.put("caseId", r.caseId());
        state.put("action", r.action().name());
        state.put("amountMinor", r.amountMinor());
        state.put("currency", r.currency());
        state.put("reason", r.reason());
        state.put("maker", r.maker());
        state.put("configVersion", r.configVersion());
        state.put("status", r.status().name());
        state.put("checker", r.checker());
        state.put("decisionNote", r.decisionNote());
        if (deniedBy != null) {
            state.put("deniedBy", deniedBy);
        }
        return state;
    }
}