package com.saumay.payments.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Bridges exception actions to authenticated identity during the Phase 8
 * migration. This resolver does not authenticate HTTP requests.
 */
final class ExceptionActorResolver {

    private ExceptionActorResolver() {
    }

    static String resolve(String header) {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null) {
            return new SecurityContextActorContext(new VerifiedJwtActorMapper())
                    .resolve(header)
                    .id();
        }

        // Temporary compatibility path for the existing unsecured HTTP setup.
        // Remove it when JWT authentication becomes mandatory.
        if (header == null || header.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "ACTOR_REQUIRED",
                    "The " + ExceptionController.ACTOR_HEADER + " header is required");
        }

        return header.trim();
    }
}