package com.saumay.payments.api;

import com.saumay.payments.application.ApprovalStore.IdentityProvenance;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Who is acting, as the server knows them. The id is the canonical lower-case user id. */
public record Actor(String id, Role role, IdentityProvenance provenance) {

    public Actor(String id, Role role) {
        this(id, role, IdentityProvenance.legacy());
    }

    public Actor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(provenance, "provenance");
    }

    /** Returns normally when the role is allowed, otherwise throws 403. */
    public void require(Set<Role> allowed) {
        if (!allowed.contains(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Your role is not allowed to do this");
        }
    }
}