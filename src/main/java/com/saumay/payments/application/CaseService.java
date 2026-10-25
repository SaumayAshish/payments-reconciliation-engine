package com.saumay.payments.application;

import com.saumay.payments.application.CaseStore.Activity;
import com.saumay.payments.application.CaseStore.Assignment;
import com.saumay.payments.application.CaseStore.CaseChange;
import com.saumay.payments.domain.cases.CaseActivityType;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.CaseTeam;
import com.saumay.payments.domain.cases.IllegalCaseTransitionException;
import com.saumay.payments.domain.cases.RoutingRules;
import java.time.Clock;
import java.util.Objects;

/**
 * The actions a person or the system can take on a case (FRD-07): assign, start investigating, escalate and
 * comment. Every action checks the case's version, runs through the {@link CaseStatus} state machine, and is
 * stored with its timeline entry in one transaction. Resolution arrives with manual matching.
 */
public final class CaseService {

    static final String RULE_MANUAL_ESCALATION = "manual-escalation";

    private static final int MAX_NAME = 100;
    private static final int MAX_REASON = 1_000;
    private static final int MAX_COMMENT = 4_000;

    private final CaseStore store;
    private final Clock clock;

    public CaseService(CaseStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CaseRecord get(long caseId) {
        return store.find(caseId).orElseThrow(() -> notFound(caseId));
    }

    /**
     * Gives an open or escalated case its first owner, or hands an assigned or investigating case to someone else
     * without changing its status. Assigning to the current owner changes nothing.
     */
    public CaseRecord assign(long caseId, long expectedVersion, String owner, String actor, String reason) {
        String newOwner = requiredName(owner, "owner");
        String by = requiredName(actor, "actor");
        String why = optionalText(reason, MAX_REASON, "reason");
        CaseRecord current = loadAtVersion(caseId, expectedVersion);

        CaseStatus status = current.status();
        Activity activity;
        if (status == CaseStatus.ASSIGNED || status == CaseStatus.INVESTIGATING) {
            if (newOwner.equals(current.owner())) {
                return current;
            }
            activity = new Activity(CaseActivityType.ASSIGNED, by, null, null,
                    "Reassigned from " + current.owner() + " to " + newOwner);
        } else {
            requireMove(status, CaseStatus.ASSIGNED);
            activity = new Activity(CaseActivityType.ASSIGNED, by, status, CaseStatus.ASSIGNED,
                    "Assigned to " + newOwner);
            status = CaseStatus.ASSIGNED;
        }
        Assignment assignment = new Assignment(newOwner, current.team(), by, why);
        return commit(current, new CaseChange(caseId, expectedVersion, status, newOwner, current.team(),
                current.priority(), current.routingRule(), activity, assignment));
    }

    /** ASSIGNED or ESCALATED to INVESTIGATING. The case must have an owner. */
    public CaseRecord startInvestigation(long caseId, long expectedVersion, String actor) {
        String by = requiredName(actor, "actor");
        CaseRecord current = loadAtVersion(caseId, expectedVersion);
        requireMove(current.status(), CaseStatus.INVESTIGATING);
        if (current.owner() == null) {
            throw new CaseRejectedException(CaseErrorCode.CASE_OWNER_REQUIRED,
                    "Case " + caseId + " has no owner; assign it before investigating");
        }
        Activity activity = new Activity(CaseActivityType.STATUS_CHANGED, by, current.status(),
                CaseStatus.INVESTIGATING, "Investigation started");
        return commit(current, new CaseChange(caseId, expectedVersion, CaseStatus.INVESTIGATING, current.owner(),
                current.team(), current.priority(), current.routingRule(), activity, null));
    }

    /**
     * Moves the case to the team lead one priority level higher and keeps its owner, SLA and due date (ADR-0008).
     * Escalating a case that is already escalated returns it unchanged: a retry is safe (EC-07.2).
     *
     * @param system true for the SLA-breach job, false for a person
     */
    public CaseRecord escalate(long caseId, long expectedVersion, String actor, String reason, boolean system) {
        String by = requiredName(actor, "actor");
        String why = optionalText(reason, MAX_REASON, "reason");
        CaseRecord current = get(caseId);
        if (current.status() == CaseStatus.ESCALATED) {
            return current;
        }
        if (current.version() != expectedVersion) {
            throw conflict(caseId);
        }
        requireMove(current.status(), CaseStatus.ESCALATED);
        String rule = system ? RoutingRules.RULE_SLA_BREACH : RULE_MANUAL_ESCALATION;
        String detail = why != null ? why : (system ? "SLA breached" : "Escalated manually");
        Activity activity = new Activity(CaseActivityType.ESCALATED, by, current.status(), CaseStatus.ESCALATED,
                detail);
        return commit(current, new CaseChange(caseId, expectedVersion, CaseStatus.ESCALATED, current.owner(),
                CaseTeam.TEAM_LEAD, current.priority().raisedOneLevel(), rule, activity, null));
    }

    /** Adds a comment. Allowed on any case, resolved ones included; it never changes the case itself. */
    public int comment(long caseId, String author, String body) {
        String by = requiredName(author, "author");
        String text = requiredText(body, MAX_COMMENT, "body");
        get(caseId);
        return store.addComment(caseId, by, text, clock.instant());
    }

    private CaseRecord loadAtVersion(long caseId, long expectedVersion) {
        CaseRecord current = get(caseId);
        if (current.version() != expectedVersion) {
            throw conflict(caseId);
        }
        return current;
    }

    private CaseRecord commit(CaseRecord before, CaseChange change) {
        if (!store.apply(change, clock.instant())) {
            throw conflict(before.id());
        }
        return get(before.id());
    }

    private static void requireMove(CaseStatus from, CaseStatus to) {
        try {
            from.requireMoveTo(to);
        } catch (IllegalCaseTransitionException e) {
            throw new CaseRejectedException(CaseErrorCode.ILLEGAL_STATE_TRANSITION,
                    "A case cannot move from " + from + " to " + to);
        }
    }

    private static CaseRejectedException notFound(long caseId) {
        return new CaseRejectedException(CaseErrorCode.CASE_NOT_FOUND, "No case exists with id " + caseId);
    }

    private static CaseRejectedException conflict(long caseId) {
        return new CaseRejectedException(CaseErrorCode.CASE_VERSION_CONFLICT,
                "Case " + caseId + " was changed by someone else; reload it and try again");
    }

    private static String requiredName(String value, String field) {
        return requiredText(value, MAX_NAME, field);
    }

    private static String requiredText(String value, int max, String field) {
        String text = optionalText(value, max, field);
        if (text == null) {
            throw invalid(field + " is required");
        }
        return text;
    }

    private static String optionalText(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.length() > max) {
            throw invalid(field + " must be at most " + max + " characters");
        }
        return text;
    }

    private static CaseRejectedException invalid(String message) {
        return new CaseRejectedException(CaseErrorCode.CASE_REQUEST_INVALID, message);
    }
}