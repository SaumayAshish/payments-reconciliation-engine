package com.saumay.payments.api;

import com.saumay.payments.application.ApprovalStore.IdentityProvenance;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps a JWT that has already been authenticated to the existing Actor model.
 *
 * This class does not verify signatures or authenticate requests. Its caller
 * must obtain the JWT from the trusted Spring Security authentication flow.
 */
public final class VerifiedJwtActorMapper {

    private final String rolesClaimName;

    public VerifiedJwtActorMapper() {
        this("roles");
    }

    public VerifiedJwtActorMapper(String rolesClaimName) {
        Objects.requireNonNull(rolesClaimName, "rolesClaimName");

        if (rolesClaimName.isBlank()
                || !rolesClaimName.equals(rolesClaimName.trim())) {
            throw new IllegalArgumentException(
                    "roles claim name must not be blank or contain surrounding whitespace");
        }

        this.rolesClaimName = rolesClaimName;
    }

    /**
     * The current API Actor model has one role. The configured claim must
     * contain exactly one recognized application role in a JSON array.
     */
    public Actor map(Jwt authenticatedJwt) {
        Objects.requireNonNull(authenticatedJwt, "authenticatedJwt");

        Object issuerClaim = authenticatedJwt.getClaims().get("iss");
        String subject = authenticatedJwt.getSubject();

        if (!(issuerClaim instanceof String issuer)
                || issuer.isBlank()
                || subject == null
                || subject.isBlank()) {
            throw authenticationRequired();
        }

        Object rolesClaim = authenticatedJwt.getClaims().get(rolesClaimName);

        if (!(rolesClaim instanceof List<?> roles)
                || roles.size() != 1
                || !(roles.get(0) instanceof String roleName)) {
            throw accessDenied();
        }

        Role role;
        try {
            role = Role.valueOf(roleName);
        } catch (IllegalArgumentException exception) {
            throw accessDenied();
        }

        return new Actor(
                OidcActorId.fromVerifiedClaims(issuer, subject),
                role,
                IdentityProvenance.oidc(issuer));
    }

    private static ApiException authenticationRequired() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "A valid authenticated identity is required");
    }

    private static ApiException accessDenied() {
        return new ApiException(
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "A single recognized application role is required");
    }
}
