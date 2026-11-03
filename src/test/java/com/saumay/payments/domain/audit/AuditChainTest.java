package com.saumay.payments.domain.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditChainTest {

    // Both expected hashes were produced by an independent implementation (Python hashlib) of the layout in
    // the AuditChain Javadoc, not by this code, so they pin the encoding as a compatibility contract.
    private static final String GOLDEN_FIRST = "b293c57eab1404c1ef0c5e4ec9016fa2e9e49ce627bfd219d3aa4fb52e419688";
    private static final String GOLDEN_SECOND = "fb0c464eac5c23ddb0a5ea56db905f4ab917593a74cae9f2f03b0533b93fe2c6";

    private static final String ZOE = "zo" + (char) 0xEB;

    private static AuditDraft first() {
        return new AuditDraft("exception_case", "42", ZOE, "CASE_ASSIGNED",
                "{\"owner\":null,\"status\":\"OPEN\"}", "{\"owner\":\"" + ZOE + "\",\"status\":\"ASSIGNED\"}",
                1793700000123456L);
    }

    private static AuditDraft second() {
        return new AuditDraft("approval_request", "7", "carol", "APPROVAL_APPROVED",
                "{\"decision\":null}", "{\"decision\":\"APPROVED\"}", 1793700060000001L);
    }

    @Test
    void theEmptyChainStartsFromThirtyTwoZeroBytes() {
        assertEquals(64, AuditChain.GENESIS_HASH.length());
        assertEquals("0".repeat(64), AuditChain.GENESIS_HASH);
        assertEquals(0L, AuditChain.GENESIS.seq());
    }

    @Test
    void theFirstEventHashMatchesTheIndependentlyComputedVector() {
        AuditEvent event = AuditChain.append(AuditChain.GENESIS, first());
        assertEquals(1L, event.seq());
        assertEquals(AuditChain.CURRENT_SCHEME, event.scheme());
        assertEquals(AuditChain.GENESIS_HASH, event.previousHash());
        assertEquals(GOLDEN_FIRST, event.currentHash());
    }

    @Test
    void theSecondEventLinksToTheFirstAndMatchesItsVector() {
        AuditEvent one = AuditChain.append(AuditChain.GENESIS, first());
        AuditEvent two = AuditChain.append(AuditChain.headOf(one), second());
        assertEquals(2L, two.seq());
        assertEquals(one.currentHash(), two.previousHash());
        assertEquals(GOLDEN_SECOND, two.currentHash());
    }

    @Test
    void recomputingAnUntouchedEventGivesItsStoredHash() {
        AuditEvent event = AuditChain.append(AuditChain.GENESIS, first());
        assertEquals(event.currentHash(), AuditChain.recompute(event));
    }

    @Test
    void sameInputsAlwaysGiveTheSameEvent() {
        assertEquals(AuditChain.append(AuditChain.GENESIS, first()), AuditChain.append(AuditChain.GENESIS, first()));
    }

    @Test
    void everyFieldOfTheEventIsPartOfItsHash() {
        AuditEvent e = AuditChain.append(AuditChain.GENESIS, first());
        String other = "f".repeat(64);
        List<AuditEvent> variants = List.of(
                new AuditEvent(e.seq() + 1, e.scheme(), e.entityType(), e.entityId(), e.actor(), e.action(),
                        e.beforeState(), e.afterState(), e.occurredAtMicros(), e.previousHash(), e.currentHash()),
                new AuditEvent(e.seq(), e.scheme(), "x", e.entityId(), e.actor(), e.action(),
                        e.beforeState(), e.afterState(), e.occurredAtMicros(), e.previousHash(), e.currentHash()),
                new AuditEvent(e.seq(), e.scheme(), e.entityType(), "43", e.actor(), e.action(),
                        e.beforeState(), e.afterState(), e.occurredAtMicros(), e.previousHash(), e.currentHash()),
                new AuditEvent(e.seq(), e.scheme(), e.entityType(), e.entityId(), "mallory", e.action(),
                        e.beforeState(), e.afterState(), e.occurredAtMicros(), e.previousHash(), e.currentHash()),
                new AuditEvent(e.seq(), e.scheme(), e.entityType(), e.entityId(), e.actor(), "CASE_RESOLVED",
                        e.beforeState(), e.afterState(), e.occurredAtMicros(), e.previousHash(), e.currentHash()),
                new AuditEvent(e.seq(), e.scheme(), e.entityType(), e.entityId(), e.actor(), e.action(),
                        "{}", e.afterState(), e.occurredAtMicros(), e.previousHash(), e.currentHash()),
                new AuditEvent(e.seq(), e.scheme(), e.entityType(), e.entityId(), e.actor(), e.action(),
                        e.beforeState(), "{}", e.occurredAtMicros(), e.previousHash(), e.currentHash()),
                new AuditEvent(e.seq(), e.scheme(), e.entityType(), e.entityId(), e.actor(), e.action(),
                        e.beforeState(), e.afterState(), e.occurredAtMicros() + 1, e.previousHash(),
                        e.currentHash()),
                new AuditEvent(e.seq(), e.scheme(), e.entityType(), e.entityId(), e.actor(), e.action(),
                        e.beforeState(), e.afterState(), e.occurredAtMicros(), other, e.currentHash()));
        for (AuditEvent variant : variants) {
            assertNotEquals(e.currentHash(), AuditChain.recompute(variant), "changed: " + variant);
        }
    }

    @Test
    void lengthPrefixesStopAFieldBoundaryFromBeingShifted() {
        AuditEvent split1 = AuditChain.append(AuditChain.GENESIS, new AuditDraft(
                "t", "ab", "c", "A", "{}", "{}", 1L));
        AuditEvent split2 = AuditChain.append(AuditChain.GENESIS, new AuditDraft(
                "t", "a", "bc", "A", "{}", "{}", 1L));
        assertNotEquals(split1.currentHash(), split2.currentHash());
    }

    @Test
    void anUnsupportedSchemeCannotBeRecomputed() {
        AuditEvent e = AuditChain.append(AuditChain.GENESIS, first());
        AuditEvent future = new AuditEvent(e.seq(), 2, e.entityType(), e.entityId(), e.actor(), e.action(),
                e.beforeState(), e.afterState(), e.occurredAtMicros(), e.previousHash(), e.currentHash());
        assertThrows(IllegalArgumentException.class, () -> AuditChain.recompute(future));
    }

    @Test
    void draftsAndEventsRefuseMalformedFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuditDraft(" ", "1", "a", "A", "{}", "{}", 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditDraft("t", "1", "a", "A", "{}", "{}", -1L));
        assertThrows(NullPointerException.class,
                () -> new AuditDraft("t", "1", "a", "A", null, "{}", 0L));
        assertThrows(IllegalArgumentException.class, () -> new AuditEvent(0, 1, "t", "1", "a", "A", "{}", "{}", 0L,
                AuditChain.GENESIS_HASH, AuditChain.GENESIS_HASH));
        assertThrows(IllegalArgumentException.class, () -> new AuditEvent(1, 1, "t", "1", "a", "A", "{}", "{}", 0L,
                "ABC", AuditChain.GENESIS_HASH));
        assertThrows(IllegalArgumentException.class, () -> new AuditEvent(1, 1, "t", "1", "a", "A", "{}", "{}", 0L,
                AuditChain.GENESIS_HASH, "G".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new AuditHead(0, "short"));
    }

    static List<AuditEvent> build(int count) {
        List<AuditEvent> events = new ArrayList<>();
        AuditHead head = AuditChain.GENESIS;
        for (int i = 1; i <= count; i++) {
            AuditEvent event = AuditChain.append(head, new AuditDraft("exception_case", String.valueOf(i), "alice",
                    "CASE_ASSIGNED", "{\"n\":" + (i - 1) + "}", "{\"n\":" + i + "}", 1_000L * i));
            events.add(event);
            head = AuditChain.headOf(event);
        }
        return events;
    }
}