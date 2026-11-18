package com.saumay.payments.api;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Restricts verified access tokens to explicitly configured OAuth clients.
 */
public final class AllowedClientValidator implements OAuth2TokenValidator<Jwt> {

    private final Set<String> allowedClientIds;

    public AllowedClientValidator(String configuredClientIds) {
        Objects.requireNonNull(configuredClientIds, "configuredClientIds");

        String[] entries = configuredClientIds.split(",", -1);

        if (Arrays.stream(entries).anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(
                    "payments.security.allowed-client-ids must contain "
                            + "one or more nonblank client IDs");
        }

        this.allowedClientIds = Arrays.stream(entries)
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Objects.requireNonNull(token, "token");

        Object clientId = token.getClaims().get("client_id");

        if (!(clientId instanceof String value)
                || !allowedClientIds.contains(value)) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token"));
        }

        return OAuth2TokenValidatorResult.success();
    }
}
