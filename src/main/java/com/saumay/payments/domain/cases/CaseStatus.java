package com.saumay.payments.domain.cases;

import java.util.EnumSet;
import java.util.Set;

/**
 * The life of an exception case (FRD-07, ADR-0007). Exactly eight moves are legal; every other pair, including a
 * move to the same state, is refused. {@code RESOLVED} is terminal: there is no reopen (Q-08).
 */
public enum CaseStatus {
    OPEN,
    ASSIGNED,
    INVESTIGATING,
    RESOLVED,
    ESCALATED;

    /** The states this one may move to. A fresh set on every call. */
    public Set<CaseStatus> legalTargets() {
        return switch (this) {
            case OPEN -> EnumSet.of(ASSIGNED, ESCALATED);
            case ASSIGNED -> EnumSet.of(INVESTIGATING, ESCALATED);
            case INVESTIGATING -> EnumSet.of(RESOLVED, ESCALATED);
            case ESCALATED -> EnumSet.of(INVESTIGATING, ASSIGNED);
            case RESOLVED -> EnumSet.noneOf(CaseStatus.class);
        };
    }

    public boolean canMoveTo(CaseStatus target) {
        return legalTargets().contains(target);
    }

    public boolean isTerminal() {
        return legalTargets().isEmpty();
    }

    /** Returns normally for a legal move, otherwise throws {@link IllegalCaseTransitionException}. */
    public void requireMoveTo(CaseStatus target) {
        if (!canMoveTo(target)) {
            throw new IllegalCaseTransitionException(this, target);
        }
    }
}