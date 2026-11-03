package com.saumay.payments.application;

import com.saumay.payments.application.CaseMatchStore.ActiveMatch;
import com.saumay.payments.application.CaseMatchStore.ClaimedLine;
import com.saumay.payments.domain.audit.CanonicalJson;
import com.saumay.payments.domain.cases.CasePriority;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.CaseTeam;
import com.saumay.payments.domain.cases.MatchLine;
import com.saumay.payments.domain.cases.ResolutionType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * Builds the audit notes of case actions (ADR-0010). One place decides which fields describe a case in the chain, so
 * every action records the same shape.
 */
final class CaseAudit {

    private CaseAudit() {
    }

    /** A change of status, owner, team or priority: the case as it was and as it will be. */
    static AuditNote note(String action, CaseRecord before, CaseStatus status, String owner, CaseTeam team,
            CasePriority priority, String routingRule) {
        return new AuditNote(action,
                CanonicalJson.write(state(before.id(), before.status(), before.owner(), before.team(),
                        before.priority(), before.routingRule(), before.version())),
                CanonicalJson.write(state(before.id(), status, owner, team, priority, routingRule,
                        before.version() + 1)));
    }

    /** A manual match or split resolving an investigating case. */
    static AuditNote resolved(long caseId, CaseStatus beforeStatus, long beforeVersion, ResolutionType type,
            String reason, List<MatchLine> lines) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("id", caseId);
        before.put("status", beforeStatus.name());
        before.put("version", beforeVersion);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", caseId);
        after.put("status", CaseStatus.RESOLVED.name());
        after.put("version", beforeVersion + 1);
        after.put("resolutionType", type.name());
        after.put("reason", reason);
        after.put("lines", describe(lines));
        return new AuditNote("CASE_RESOLVED", CanonicalJson.write(before), CanonicalJson.write(after));
    }

    /** Reversing a match: the claims released, and the case opened in their place once the store knows its id. */
    static LongFunction<AuditNote> reversed(ActiveMatch match, String reason) {
        List<Long> claimSeqs = new ArrayList<>();
        List<MatchLine> lines = new ArrayList<>();
        for (ClaimedLine claimed : match.lines()) {
            claimSeqs.add(claimed.claimSeq());
            lines.add(claimed.line());
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("claimSeqs", claimSeqs);
        before.put("lines", describe(lines));
        before.put("linkedCaseId", match.linkedCaseId());
        String beforeJson = CanonicalJson.write(before);
        return newCaseId -> {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("reason", reason);
            after.put("reopenedAs", newCaseId);
            return new AuditNote("CASE_MATCH_REVERSED", beforeJson, CanonicalJson.write(after));
        };
    }

    private static Map<String, Object> state(long id, CaseStatus status, String owner, CaseTeam team,
            CasePriority priority, String routingRule, long version) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", id);
        state.put("status", status.name());
        state.put("owner", owner);
        state.put("team", team.name());
        state.put("priority", priority.name());
        state.put("routingRule", routingRule);
        state.put("version", version);
        return state;
    }

    private static List<String> describe(List<MatchLine> lines) {
        List<String> described = new ArrayList<>(lines.size());
        for (MatchLine line : lines) {
            described.add(line.side().name() + ":" + line.seq());
        }
        return described;
    }
}