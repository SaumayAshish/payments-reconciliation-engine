package com.saumay.payments.domain.approval;

import java.time.Instant;
import java.util.Objects;

/**
 * A controlled action waiting for, or carrying, a second person's decision (FRD-08). Immutable: a decision returns a
 * new value. Decided requests are final. {@code id} is null until stored.
 */
public record ApprovalRequest(
        Long id,
        long caseId,
        ControlledAction action,
        long amountMinor,
        String currency,
        String reason,
        String maker,
        Instant requestedAt,
        long configVersion,
        ApprovalStatus status,
        String checker,
        Instant decidedAt,
        String decisionNote) {

    public ApprovalRequest {
        if (caseId <= 0) {
            throw new IllegalArgumentException("caseId must be positive");
        }
        Objects.requireNonNull(action, "action");
        if (amountMinor == 0) {
            throw new IllegalArgumentException("a controlled action needs a non-zero amount");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be three upper-case letters");
        }
        reason = requireText(reason, "reason");
        maker = requireActor(maker, "maker");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (configVersion < 0) {
            throw new IllegalArgumentException("configVersion cannot be negative");
        }
        Objects.requireNonNull(status, "status");
        switch (status) {
            case PENDING -> {
                if (checker != null || decidedAt != null || decisionNote != null) {
                    throw new IllegalArgumentException("a pending request has no decision");
                }
            }
            case APPROVED, REJECTED -> {
                checker = requireActor(checker, "checker");
                Objects.requireNonNull(decidedAt, "decidedAt");
                MakerCheckerRule.requireDistinct(maker, checker);
                if (status == ApprovalStatus.REJECTED) {
                    decisionNote = requireText(decisionNote, "decisionNote");
                } else if (decisionNote != null && decisionNote.isBlank()) {
                    decisionNote = null;
                }
            }
        }
    }

    public static ApprovalRequest pending(long caseId, ControlledAction action, long amountMinor, String currency,
            String reason, String maker, Instant requestedAt, long configVersion) {
        return new ApprovalRequest(null, caseId, action, amountMinor, currency, reason, maker, requestedAt,
                configVersion, ApprovalStatus.PENDING, null, null, null);
    }

    /** Already-decided is reported before separation of duties: a closed request has nothing left to protect. */
    public ApprovalRequest approve(String checker, Instant at, String note) {
        requirePending();
        MakerCheckerRule.requireDistinct(maker, checker);
        return new ApprovalRequest(id, caseId, action, amountMinor, currency, reason, maker, requestedAt,
                configVersion, ApprovalStatus.APPROVED, checker, at, note);
    }

    public ApprovalRequest reject(String checker, Instant at, String note) {
        requirePending();
        MakerCheckerRule.requireDistinct(maker, checker);
        return new ApprovalRequest(id, caseId, action, amountMinor, currency, reason, maker, requestedAt,
                configVersion, ApprovalStatus.REJECTED, checker, at, note);
    }

    private void requirePending() {
        if (status != ApprovalStatus.PENDING) {
            throw new ApprovalAlreadyDecidedException(status);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String requireActor(String value, String name) {
        String text = requireText(value, name);
        if (text.length() > 100) {
            throw new IllegalArgumentException(name + " is longer than 100 characters");
        }
        return text;
    }
}