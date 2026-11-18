package com.saumay.payments.api;

import java.net.URI;

/**
 * Validates the configured OIDC issuer without normalizing its value.
 */
final class IssuerUriPolicy {

    private static final int MAX_ISSUER_LENGTH = 2048;

    private IssuerUriPolicy() {
    }

    static String validate(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_ISSUER_LENGTH) {
            throw invalid();
        }

        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }

        if (!uri.isAbsolute()
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getPort() == 0
                || uri.getPort() > 65535) {
            throw invalid();
        }

        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "OIDC issuer must be an HTTPS URL without query, fragment, or user information "
                        + "and at most 2048 characters");
    }
}