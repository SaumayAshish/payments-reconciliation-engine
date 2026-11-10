package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.ApprovalErrorCode;
import com.saumay.payments.application.ApprovalRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Coordinates case actions with approval submissions through the case row lock. */
final class PendingApprovalLock {

    private static final String LOCK_CASE =
            "SELECT id FROM exception_case WHERE id = ? FOR UPDATE";
    private static final String HAS_PENDING =
            "SELECT EXISTS (SELECT 1 FROM approval_request WHERE case_id = ? AND status = 'PENDING')";

    private PendingApprovalLock() {
    }

    static void requireNone(JdbcTemplate jdbc, long caseId) {
        if (jdbc.queryForList(LOCK_CASE, Long.class, caseId).isEmpty()) {
            return;
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(HAS_PENDING, Boolean.class, caseId))) {
            throw new ApprovalRejectedException(ApprovalErrorCode.APPROVAL_PENDING,
                    "Case " + caseId + " has a pending approval request; decide it first");
        }
    }
}