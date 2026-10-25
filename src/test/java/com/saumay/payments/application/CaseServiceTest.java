package com.saumay.payments.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.application.CaseStore.CaseChange;
import com.saumay.payments.domain.cases.CaseActivityType;
import com.saumay.payments.domain.cases.CaseClass;
import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CasePriority;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.CaseTeam;
import com.saumay.payments.domain.cases.RoutingRules;
import com.saumay.payments.domain.money.CurrencyCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class CaseServiceTest {

    // 2026-10-09 is a Friday.
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    private final FakeStore store = new FakeStore();
    private final CaseService service = new CaseService(store, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void assigningAnOpenCaseMovesItToAssignedAndRecordsWhoAndWhy() {
        store.put(aCase(CaseStatus.OPEN, null, 0));

        CaseRecord after = service.assign(1, 0, "alice", "lead", "knows the ledger");

        assertEquals(CaseStatus.ASSIGNED, after.status());
        assertEquals("alice", after.owner());
        assertEquals(1, after.version());
        CaseChange change = store.lastChange();
        assertEquals(CaseActivityType.ASSIGNED, change.activity().type());
        assertEquals(CaseStatus.OPEN, change.activity().from());
        assertEquals(CaseStatus.ASSIGNED, change.activity().to());
        assertEquals("lead", change.assignment().assignedBy());
        assertEquals("knows the ledger", change.assignment().reason());
        assertEquals(CaseTeam.LEDGER_OPERATIONS, change.assignment().team());
    }

    @Test
    void reassigningKeepsTheStatusAndLeavesTheOldOwnerInTheHistory() {
        store.put(aCase(CaseStatus.INVESTIGATING, "alice", 3));

        CaseRecord after = service.assign(1, 3, "bob", "lead", null);

        assertEquals(CaseStatus.INVESTIGATING, after.status());
        assertEquals("bob", after.owner());
        CaseChange change = store.lastChange();
        assertNull(change.activity().from());
        assertNull(change.activity().to());
        assertTrue(change.activity().detail().contains("alice") && change.activity().detail().contains("bob"));
        assertEquals("bob", change.assignment().owner());
    }

    @Test
    void assigningToTheCurrentOwnerChangesNothing() {
        store.put(aCase(CaseStatus.ASSIGNED, "alice", 2));

        CaseRecord after = service.assign(1, 2, "alice", "lead", null);

        assertEquals(2, after.version());
        assertTrue(store.changes.isEmpty());
    }

    @Test
    void anEscalatedCaseCanBeAssignedAgain() {
        store.put(aCase(CaseStatus.ESCALATED, null, 4));
        assertEquals(CaseStatus.ASSIGNED, service.assign(1, 4, "carol", "lead", null).status());
    }

    @Test
    void aResolvedCaseCannotBeAssignedOrEscalated() {
        store.put(aCase(CaseStatus.RESOLVED, "alice", 5));
        assertRejected(CaseErrorCode.ILLEGAL_STATE_TRANSITION, () -> service.assign(1, 5, "bob", "lead", null));
        assertRejected(CaseErrorCode.ILLEGAL_STATE_TRANSITION,
                () -> service.escalate(1, 5, "lead", "late", false));
        assertRejected(CaseErrorCode.ILLEGAL_STATE_TRANSITION, () -> service.startInvestigation(1, 5, "alice"));
        assertTrue(store.changes.isEmpty());
    }

    @Test
    void startingAnInvestigationMovesAnAssignedCaseToInvestigating() {
        store.put(aCase(CaseStatus.ASSIGNED, "alice", 1));

        CaseRecord after = service.startInvestigation(1, 1, "alice");

        assertEquals(CaseStatus.INVESTIGATING, after.status());
        assertEquals(CaseActivityType.STATUS_CHANGED, store.lastChange().activity().type());
    }

    @Test
    void anOpenCaseCannotSkipAssignment() {
        store.put(aCase(CaseStatus.OPEN, null, 0));
        assertRejected(CaseErrorCode.ILLEGAL_STATE_TRANSITION, () -> service.startInvestigation(1, 0, "alice"));
    }

    @Test
    void anEscalatedCaseWithNoOwnerNeedsOneBeforeInvestigating() {
        store.put(aCase(CaseStatus.ESCALATED, null, 2));
        assertRejected(CaseErrorCode.CASE_OWNER_REQUIRED, () -> service.startInvestigation(1, 2, "lead"));
    }

    @Test
    void aPersonEscalatingSendsTheCaseToTheTeamLeadOneLevelUpAndKeepsTheOwner() {
        store.put(aCase(CaseStatus.INVESTIGATING, "alice", 2));

        CaseRecord after = service.escalate(1, 2, "alice", "needs authority", false);

        assertEquals(CaseStatus.ESCALATED, after.status());
        assertEquals(CaseTeam.TEAM_LEAD, after.team());
        assertEquals(CasePriority.P2, after.priority());
        assertEquals("alice", after.owner());
        assertEquals(CaseService.RULE_MANUAL_ESCALATION, after.routingRule());
        assertEquals(FRIDAY.plusDays(7), after.dueBusinessDate());
        assertEquals("needs authority", store.lastChange().activity().detail());
    }

    @Test
    void theSlaJobEscalatesUnderTheSlaBreachRule() {
        store.put(aCase(CaseStatus.OPEN, null, 0));
        CaseRecord after = service.escalate(1, 0, "system", null, true);
        assertEquals(RoutingRules.RULE_SLA_BREACH, after.routingRule());
        assertEquals("SLA breached", store.lastChange().activity().detail());
    }

    @Test
    void escalatingAnAlreadyEscalatedCaseIsASafeRetry() {
        store.put(aCase(CaseStatus.ESCALATED, "alice", 7));

        CaseRecord after = service.escalate(1, 0, "lead", "again", false);

        assertEquals(7, after.version());
        assertTrue(store.changes.isEmpty());
    }

    @Test
    void aStaleVersionIsRefusedAndNothingIsWritten() {
        store.put(aCase(CaseStatus.OPEN, null, 3));
        assertRejected(CaseErrorCode.CASE_VERSION_CONFLICT, () -> service.assign(1, 2, "alice", "lead", null));
        assertRejected(CaseErrorCode.CASE_VERSION_CONFLICT,
                () -> service.escalate(1, 2, "lead", null, false));
        assertTrue(store.changes.isEmpty());
    }

    @Test
    void losingTheRaceAtTheStoreIsReportedAsAConflict() {
        store.put(aCase(CaseStatus.OPEN, null, 0));
        store.loseNextApply = true;
        assertRejected(CaseErrorCode.CASE_VERSION_CONFLICT, () -> service.assign(1, 0, "alice", "lead", null));
    }

    @Test
    void anUnknownCaseIsNotFound() {
        assertRejected(CaseErrorCode.CASE_NOT_FOUND, () -> service.assign(99, 0, "alice", "lead", null));
        assertRejected(CaseErrorCode.CASE_NOT_FOUND, () -> service.comment(99, "alice", "hello"));
        assertRejected(CaseErrorCode.CASE_NOT_FOUND, () -> service.get(99));
    }

    @Test
    void blankOrOversizedInputIsRefused() {
        store.put(aCase(CaseStatus.OPEN, null, 0));
        assertRejected(CaseErrorCode.CASE_REQUEST_INVALID, () -> service.assign(1, 0, "  ", "lead", null));
        assertRejected(CaseErrorCode.CASE_REQUEST_INVALID, () -> service.assign(1, 0, "alice", null, null));
        assertRejected(CaseErrorCode.CASE_REQUEST_INVALID,
                () -> service.assign(1, 0, "alice", "lead", "x".repeat(1_001)));
        assertRejected(CaseErrorCode.CASE_REQUEST_INVALID, () -> service.comment(1, "alice", " "));
        assertRejected(CaseErrorCode.CASE_REQUEST_INVALID, () -> service.comment(1, "alice", "x".repeat(4_001)));
        assertTrue(store.changes.isEmpty());
    }

    @Test
    void aCommentIsAllowedOnAnyCaseIncludingAResolvedOne() {
        store.put(aCase(CaseStatus.RESOLVED, "alice", 5));
        assertEquals(1, service.comment(1, "auditor", "  reviewed  "));
        assertEquals(2, service.comment(1, "alice", "thanks"));
        assertEquals(List.of("reviewed", "thanks"), store.comments);
    }

    private static void assertRejected(CaseErrorCode code, Executable action) {
        CaseRejectedException e = assertThrows(CaseRejectedException.class, action);
        assertEquals(code, e.code());
    }

    private static CaseRecord aCase(CaseStatus status, String owner, long version) {
        return new CaseRecord(1, CaseKind.MISSING_LEDGER, CaseClass.BREAK, 3_000, CurrencyCode.of("GBP"), status,
                CasePriority.P3, CaseTeam.LEDGER_OPERATIONS, RoutingRules.RULE_MISSING_LEDGER, owner, FRIDAY, 5,
                FRIDAY.plusDays(7), version);
    }

    /** In-memory store with the same version rule as the database. */
    private static final class FakeStore implements CaseStore {
        final Map<Long, CaseRecord> cases = new HashMap<>();
        final List<CaseChange> changes = new ArrayList<>();
        final List<String> comments = new ArrayList<>();
        boolean loseNextApply;

        void put(CaseRecord record) {
            cases.put(record.id(), record);
        }

        CaseChange lastChange() {
            return changes.get(changes.size() - 1);
        }

        @Override
        public Optional<CaseRecord> find(long caseId) {
            return Optional.ofNullable(cases.get(caseId));
        }

        @Override
        public boolean apply(CaseChange change, Instant at) {
            CaseRecord c = cases.get(change.caseId());
            if (loseNextApply) {
                loseNextApply = false;
                return false;
            }
            if (c == null || c.version() != change.expectedVersion()) {
                return false;
            }
            cases.put(c.id(), new CaseRecord(c.id(), c.kind(), c.caseClass(), c.amountMinor(), c.currency(),
                    change.status(), change.priority(), change.team(), change.routingRule(), change.owner(),
                    c.createdBusinessDate(), c.slaBusinessDays(), c.dueBusinessDate(), c.version() + 1));
            changes.add(change);
            return true;
        }

        @Override
        public int addComment(long caseId, String author, String body, Instant at) {
            comments.add(body);
            return comments.size();
        }
    }
}