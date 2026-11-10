package com.saumay.payments.api;

import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Who is acting, as the server knows them. The id is the canonical lower-case user id. */
public record Actor(String id, Role role) {

    public Actor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
    }

    /** Returns normally when the role is allowed, otherwise throws 403. */
    public void require(Set<Role> allowed) {
        if (!allowed.contains(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Your role is not allowed to do this");
        }
    }
}