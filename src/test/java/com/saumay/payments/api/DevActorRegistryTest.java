package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DevActorRegistryTest {

    private final DevActorRegistry registry = new DevActorRegistry();

    @Test
    void aKnownUserResolvesToTheirRoleWhateverTheCase() {
        assertEquals(new Actor("alice", Role.ANALYST), registry.resolve("alice"));
        assertEquals(new Actor("carol", Role.CONTROLLER), registry.resolve(" CAROL "));
        assertEquals(Role.AUDITOR, registry.resolve("erin").role());
        assertEquals(Role.ADMIN, registry.resolve("root").role());
    }

    @Test
    void aMissingHeaderIsABadRequest() {
        ApiException blank = assertThrows(ApiException.class, () -> registry.resolve("  "));
        assertEquals(HttpStatus.BAD_REQUEST, blank.status());
        assertEquals("ACTOR_REQUIRED", blank.code());
        assertEquals("ACTOR_REQUIRED", assertThrows(ApiException.class, () -> registry.resolve(null)).code());
    }

    @Test
    void anUnknownUserIsUnauthorised() {
        ApiException unknown = assertThrows(ApiException.class, () -> registry.resolve("mallory"));
        assertEquals(HttpStatus.UNAUTHORIZED, unknown.status());
        assertEquals("UNKNOWN_ACTOR", unknown.code());
    }

    @Test
    void aRoleOutsideTheAllowedSetIsForbidden() {
        Actor analyst = registry.resolve("alice");
        ApiException refused = assertThrows(ApiException.class,
                () -> analyst.require(EnumSet.of(Role.CONTROLLER)));
        assertEquals(HttpStatus.FORBIDDEN, refused.status());
        assertEquals("ACCESS_DENIED", refused.code());
    }

    @Test
    void outsideTheDevProfileNobodyCanAct() {
        ApiException refused = assertThrows(ApiException.class, () -> new FailClosedActorContext().resolve("alice"));
        assertEquals(HttpStatus.UNAUTHORIZED, refused.status());
        assertEquals("AUTHENTICATION_REQUIRED", refused.code());
    }
}