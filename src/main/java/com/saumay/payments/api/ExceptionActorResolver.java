package com.saumay.payments.api;

/**
 * Resolves exception actions from the authenticated security context.
 * Caller-provided actor headers cannot establish or override identity.
 */
final class ExceptionActorResolver {

    private ExceptionActorResolver() {
    }

    static String resolve(String header) {
        return new SecurityContextActorContext(new VerifiedJwtActorMapper())
                .resolve(header)
                .id();
    }
}