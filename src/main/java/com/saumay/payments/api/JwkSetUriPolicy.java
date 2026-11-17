package com.saumay.payments.api;

import java.net.URI;

/**
 * Restricts the configured JWK endpoint before the JWT decoder is created.
 */
final class JwkSetUriPolicy {

    private JwkSetUriPolicy() {
    }

    static String validate(String value, boolean allowInsecureLoopback) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }

        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }

        if (!uri.isAbsolute()
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getPort() == 0
                || uri.getPort() > 65535) {
            throw invalid();
        }

        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return value;
        }

        if (allowInsecureLoopback
                && "http".equalsIgnoreCase(uri.getScheme())
                && "127.0.0.1".equals(uri.getHost())) {
            return value;
        }

        throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "JWK set URI must use HTTPS; HTTP is allowed only for explicitly enabled 127.0.0.1 testing");
    }
}