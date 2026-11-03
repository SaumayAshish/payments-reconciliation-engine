package com.saumay.payments.application;

import java.util.Objects;

/**
 * What an action wants recorded in the audit chain: its name and the entity's state before and after, already
 * written as canonical JSON. The store that runs the action's transaction appends it as the last statement, adding
 * the entity id, the actor and the time it alone knows (ADR-0010).
 */
public record AuditNote(String action, String beforeState, String afterState) {

    public AuditNote {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(beforeState, "beforeState");
        Objects.requireNonNull(afterState, "afterState");
    }
}