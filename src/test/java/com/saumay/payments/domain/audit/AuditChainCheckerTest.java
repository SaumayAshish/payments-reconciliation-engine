package com.saumay.payments.domain.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.audit.AuditVerification.FailureReason;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditChainCheckerTest {

    private static AuditVerification verify(List<AuditEvent> events) {
        AuditChainChecker checker = new AuditChainChecker();
        for (AuditEvent event : events) {
            if (!checker.accept(event)) {
                break;
            }
        }
        return checker.result();
    }

    private static AuditEvent withAfterState(AuditEvent e, String state, String currentHash) {
        return new AuditEvent(e.seq(), e.scheme(), e.entityType(), e.entityId(), e.actor(), e.action(),
                e.beforeState(), state, e.occurredAtMicros(), e.previousHash(), currentHash);
    }

    private static AuditEvent relinked(AuditEvent e, String previousHash, String state) {
        AuditEvent draft = new AuditEvent(e.seq(), e.scheme(), e.entityType(), e.entityId(), e.actor(), e.action(),
                e.beforeState(), state, e.occurredAtMicros(), previousHash, e.currentHash());
        return new AuditEvent(draft.seq(), draft.scheme(), draft.entityType(), draft.entityId(), draft.actor(),
                draft.action(), draft.beforeState(), draft.afterState(), draft.occurredAtMicros(),
                draft.previousHash(), AuditChain.recompute(draft));
    }

    @Test
    void anEmptyChainIsIntactAndHasTheGenesisHead() {
        assertEquals(new AuditVerification.Ok(0, 0, AuditChain.GENESIS_HASH), verify(List.of()));
    }

    @Test
    void anUntouchedChainVerifiesAndReportsItsHead() {
        List<AuditEvent> events = AuditChainTest.build(25);
        AuditEvent last = events.get(24);
        assertEquals(new AuditVerification.Ok(25, 25, last.currentHash()), verify(events));
    }

    @Test
    void anEditedEventIsAHashMismatchAtExactlyThatPosition() {
        List<AuditEvent> events = new ArrayList<>(AuditChainTest.build(6));
        AuditEvent victim = events.get(2);
        events.set(2, withAfterState(victim, "{\"n\":999}", victim.currentHash()));
        assertEquals(new AuditVerification.Failed(3, FailureReason.HASH_MISMATCH, 2), verify(events));
    }

    @Test
    void anEditedAndRehashedEventBreaksTheLinkOfTheNextOne() {
        List<AuditEvent> events = new ArrayList<>(AuditChainTest.build(6));
        events.set(2, relinked(events.get(2), events.get(2).previousHash(), "{\"n\":999}"));
        assertEquals(new AuditVerification.Failed(4, FailureReason.CHAIN_BREAK, 3), verify(events));
    }

    @Test
    void aDeletedEventIsASequenceGapAtTheMissingNumber() {
        List<AuditEvent> events = new ArrayList<>(AuditChainTest.build(6));
        events.remove(2);
        assertEquals(new AuditVerification.Failed(3, FailureReason.SEQ_GAP, 2), verify(events));
    }

    @Test
    void aRepeatedEventIsASequenceGap() {
        List<AuditEvent> events = new ArrayList<>(AuditChainTest.build(3));
        events.add(events.get(2));
        assertEquals(new AuditVerification.Failed(4, FailureReason.SEQ_GAP, 3), verify(events));
    }

    @Test
    void theFirstEventMustLinkToTheGenesisHash() {
        List<AuditEvent> events = new ArrayList<>(AuditChainTest.build(3));
        events.set(0, relinked(events.get(0), "a".repeat(64), events.get(0).afterState()));
        assertEquals(new AuditVerification.Failed(1, FailureReason.CHAIN_BREAK, 0), verify(events));
    }

    @Test
    void anUnsupportedSchemeIsReportedAsAHashMismatch() {
        List<AuditEvent> events = new ArrayList<>(AuditChainTest.build(3));
        AuditEvent e = events.get(1);
        events.set(1, new AuditEvent(e.seq(), 2, e.entityType(), e.entityId(), e.actor(), e.action(),
                e.beforeState(), e.afterState(), e.occurredAtMicros(), e.previousHash(), e.currentHash()));
        assertEquals(new AuditVerification.Failed(2, FailureReason.HASH_MISMATCH, 1), verify(events));
    }

    @Test
    void afterTheFirstFailureNothingElseIsAcceptedAndTheResultDoesNotChange() {
        List<AuditEvent> events = new ArrayList<>(AuditChainTest.build(5));
        AuditEvent victim = events.get(1);
        AuditChainChecker checker = new AuditChainChecker();
        assertTrue(checker.accept(events.get(0)));
        assertFalse(checker.accept(withAfterState(victim, "{}", victim.currentHash())));
        AuditVerification failed = checker.result();
        assertFalse(checker.accept(events.get(1)));
        assertFalse(checker.accept(events.get(2)));
        assertEquals(failed, checker.result());
    }

    @Test
    void rewritingTheWholeTailIsInternallyConsistentSoOnlyAnAnchoredHeadCatchesIt() {
        // The documented limit (ADR-0004, ADR-0010): someone who can rewrite every event from a point on,
        // recomputing each hash, produces a chain that verifies. The defence is a head recorded elsewhere.
        List<AuditEvent> original = AuditChainTest.build(6);
        AuditHead anchored = AuditChain.headOf(original.get(5));

        List<AuditEvent> forged = new ArrayList<>(original.subList(0, 2));
        AuditHead head = AuditChain.headOf(forged.get(1));
        for (int i = 2; i < 6; i++) {
            AuditEvent e = original.get(i);
            AuditEvent rewritten = AuditChain.append(head, new AuditDraft(e.entityType(), e.entityId(), e.actor(),
                    e.action(), e.beforeState(), "{\"n\":-1}", e.occurredAtMicros()));
            forged.add(rewritten);
            head = AuditChain.headOf(rewritten);
        }

        AuditVerification result = verify(forged);
        assertTrue(result instanceof AuditVerification.Ok);
        assertNotEquals(anchored.hash(), ((AuditVerification.Ok) result).headHash());
    }
}