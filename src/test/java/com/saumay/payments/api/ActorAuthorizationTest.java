package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ActorAuthorizationTest {

    @Test
    void aRoleOutsideTheAllowedSetIsForbidden() {
        Actor analyst = new Actor("alice", Role.ANALYST);

        ApiException refused = assertThrows(
                ApiException.class,
                () -> analyst.require(EnumSet.of(Role.CONTROLLER)));

        assertEquals(HttpStatus.FORBIDDEN, refused.status());
        assertEquals("ACCESS_DENIED", refused.code());
    }

    @Test
    void failClosedContextDoesNotTrustAnActorHeader() {
        ApiException refused = assertThrows(
                ApiException.class,
                () -> new FailClosedActorContext().resolve("alice"));

        assertEquals(HttpStatus.UNAUTHORIZED, refused.status());
        assertEquals("AUTHENTICATION_REQUIRED", refused.code());
    }
}