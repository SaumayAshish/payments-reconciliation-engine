package com.saumay.payments.api;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Without OIDC, no actor can be resolved. */
@Component
@Profile("!oidc")
public final class FailClosedActorContext implements ActorContext {

    @Override
    public Actor resolve(String header) {
        throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                "Authentication is not configured for this environment");
    }
}