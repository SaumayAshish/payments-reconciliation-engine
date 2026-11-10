package com.saumay.payments.api;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Outside the dev profile nobody can act until real authentication exists (Phase 8): the safe default. */
@Component
@Profile("!dev")
public final class FailClosedActorContext implements ActorContext {

    @Override
    public Actor resolve(String header) {
        throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                "Authentication is not configured for this environment");
    }
}