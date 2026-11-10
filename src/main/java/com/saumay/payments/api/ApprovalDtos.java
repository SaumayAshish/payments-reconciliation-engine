package com.saumay.payments.api;

import com.saumay.payments.domain.approval.ApprovalRequest;
import java.time.Instant;

/** The wire shapes of the approval API. Money is integer minor units plus a currency code. */
public final class ApprovalDtos {

    private ApprovalDtos() {
    }

    /** action is WRITE_OFF or ADJUSTMENT. The case must be INVESTIGATING at expectedVersion. */
    public record ResolveRequest(Long expectedVersion, String action, String reason) {
    }

    /** A note is optional when approving and required when rejecting. */
    public record DecisionRequest(String note) {
    }

    public record ApprovalDto(long id, long caseId, String action, long amountMinor, String currency, String reason,
            String maker, String requestedAt, long configVersion, String status, String checker, String decidedAt,
            String decisionNote) {

        static ApprovalDto from(ApprovalRequest r) {
            return new ApprovalDto(r.id(), r.caseId(), r.action().name(), r.amountMinor(), r.currency(), r.reason(),
                    r.maker(), r.requestedAt().toString(), r.configVersion(), r.status().name(), r.checker(),
                    text(r.decidedAt()), r.decisionNote());
        }

        private static String text(Instant instant) {
            return instant == null ? null : instant.toString();
        }
    }
}