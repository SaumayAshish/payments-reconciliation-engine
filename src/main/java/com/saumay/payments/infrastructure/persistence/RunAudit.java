package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.domain.audit.AuditDraft;
import com.saumay.payments.domain.audit.AuditTime;
import com.saumay.payments.domain.audit.CanonicalJson;
import com.saumay.payments.matching.RunResult;
import com.saumay.payments.matching.RunSummary;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** The audit event of a completed run (ADR-0010): its outcome and the output hash that replay is checked against. */
final class RunAudit {

    static final String SYSTEM_ACTOR = "system";

    private RunAudit() {
    }

    static AuditDraft completed(long runId, RunResult result, Instant at) {
        RunSummary s = result.summary();
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", "RUNNING");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "COMPLETED");
        after.put("outputHash", result.outputHash().hex());
        after.put("statementLines", s.statementLines());
        after.put("ledgerEntries", s.ledgerEntries());
        after.put("matches", s.matches());
        after.put("autoConfirmedMatches", s.autoConfirmedMatches());
        after.put("reviewMatches", s.reviewMatches());
        after.put("exceptions", s.exceptions());
        return new AuditDraft("reconciliation_run", String.valueOf(runId), SYSTEM_ACTOR, "RUN_COMPLETED",
                CanonicalJson.write(before), CanonicalJson.write(after), AuditTime.micros(at));
    }
}