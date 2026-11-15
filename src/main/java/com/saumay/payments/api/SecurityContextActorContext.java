package com.saumay.payments.api;

import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Resolves an actor from Spring Security's authenticated context.
 *
 * The application must only register this adapter with a security filter chain
 * that verifies JWT signatures and validates the required claims before
 * placing a Jwt principal in the security context.
 */
public final class SecurityContextActorContext implements ActorContext {

    private final VerifiedJwtActorMapper mapper;

    public SecurityContextActorContext(VerifiedJwtActorMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * The header argument is ignored. Caller-supplied identity headers cannot
     * choose the actor used for audit or maker-checker operations.
     */
    @Override
    public Actor resolve(String ignoredHeader) {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "A valid authenticated identity is required");
        }

        return mapper.map(jwt);
    }
}