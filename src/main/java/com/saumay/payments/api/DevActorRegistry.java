package com.saumay.payments.api;

import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Fixed demo users for local development and tests. Active only under the dev profile (ADR-0011). */
@Component
@Profile("dev")
public final class DevActorRegistry implements ActorContext {

    private static final Map<String, Role> USERS = Map.of(
            "alice", Role.ANALYST,
            "bob", Role.TEAM_LEAD,
            "carol", Role.CONTROLLER,
            "dave", Role.CONTROLLER,
            "erin", Role.AUDITOR,
            "root", Role.ADMIN);

    @Override
    public Actor resolve(String header) {
        if (header == null || header.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACTOR_REQUIRED",
                    "The " + ExceptionController.ACTOR_HEADER + " header is required");
        }
        String id = header.trim().toLowerCase(Locale.ROOT);
        Role role = USERS.get(id);
        if (role == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNKNOWN_ACTOR", "The acting user is not known");
        }
        return new Actor(id, role);
    }
}