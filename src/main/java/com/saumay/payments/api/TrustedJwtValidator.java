package com.saumay.payments.api;

import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * Validates claims on a JWT that has already passed signature verification.
 * The decoder using this validator must itself verify the signature.
 */
public final class TrustedJwtValidator implements OAuth2TokenValidator<Jwt> {

    private final String audience;
    private final OAuth2TokenValidator<Jwt> standardValidator;

    public TrustedJwtValidator(String issuer, String audience) {
        this.audience = required(audience, "audience");
        this.standardValidator = JwtValidators.createDefaultWithIssuer(
                required(issuer, "issuer"));
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Objects.requireNonNull(token, "token");

        OAuth2TokenValidatorResult standard = standardValidator.validate(token);
        if (standard.hasErrors()) {
            return standard;
        }

        List<String> audiences = token.getAudience();
        if (token.getExpiresAt() == null
                || token.getSubject() == null
                || token.getSubject().isBlank()
                || audiences == null
                || !audiences.contains(audience)) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token"));
        }

        return OAuth2TokenValidatorResult.success();
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}