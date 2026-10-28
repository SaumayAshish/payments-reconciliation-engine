package com.saumay.payments.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saumay.payments.application.CaseMatchStore.ActiveMatch;
import com.saumay.payments.application.CaseMatchStore.CaseState;
import com.saumay.payments.application.CaseMatchStore.ClaimedLine;
import com.saumay.payments.application.CaseMatchStore.NewUnmatchCase;
import com.saumay.payments.application.CaseMatchStore.Resolution;
import com.saumay.payments.application.CaseMatchStore.ResolveOutcome;
import com.saumay.payments.application.CaseMatchStore.SubjectLine;
import com.saumay.payments.domain.cases.CasePriority;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.CaseTeam;
import com.saumay.payments.domain.cases.MatchLine;
import com.saumay.payments.domain.cases.MatchLine.Side;
import com.saumay.payments.domain.cases.ResolutionType;
import com.saumay.payments.domain.cases.RoutingConfig;
import com.saumay.payments.domain.model.BusinessCalendar;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class CaseMatchServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T09:00:00Z"), ZoneOffset.UTC);
    private static final long CASE_ID = 7;

    private final FakeStore store = new FakeStore();
    private final CaseMatchService service = new CaseMatchService(store, RoutingConfig.defaults(), CLOCK);

    @Test
    void aOneToOneMatchResolvesAnInvestigatingCase() {
        service.resolve(CASE_ID, 3, " alice ", " same payer, new reference ", List.of(10L), List.of(20L));

        Resolution r = store.resolved;
        assertEquals(ResolutionType.MANUAL_MATCH, r.type());
        assertEquals("alice", r.actor());
        assertEquals("same payer, new reference", r.reason());
        assertEquals(3, r.expectedVersion());
        assertEquals(2, r.lines().size());
    }

    @Test
    void oneLineAgainstSeveralIsStoredAsASplit() {
        service.resolve(CASE_ID, 3, "alice", "paid in two parts", List.of(10L), List.of(21L, 22L));

        assertEquals(ResolutionType.SPLIT, store.resolved.type());
        assertEquals(3, store.resolved.lines().size());
    }

    @Test
    void aCaseThatIsNotInvestigatingCannotBeResolved() {
        store.state = new CaseState(CaseStatus.ASSIGNED, 3);

        assertCode(CaseErrorCode.ILLEGAL_STATE_TRANSITION, this::resolveDefault);
    }

    @Test
    void aStaleVersionIsRefusedBeforeAnythingElse() {
        assertCode(CaseErrorCode.CASE_VERSION_CONFLICT,
                () -> service.resolve(CASE_ID, 2, "alice", "why", List.of(10L), List.of(20L)));
        assertNull(store.resolved);
    }

    @Test
    void anUnknownCaseIsNotFound() {
        store.caseExists = false;

        assertCode(CaseErrorCode.CASE_NOT_FOUND, this::resolveDefault);
    }

    @Test
    void aMatchWithoutAReasonIsRefused() {
        assertCode(CaseErrorCode.CASE_REQUEST_INVALID,
                () -> service.resolve(CASE_ID, 3, "alice", "  ", List.of(10L), List.of(20L)));
    }

    @Test
    void aLineThatDoesNotExistIsRefused() {
        assertCode(CaseErrorCode.MATCH_LINE_NOT_FOUND,
                () -> service.resolve(CASE_ID, 3, "alice", "why", List.of(10L), List.of(999L)));
    }

    @Test
    void theMatchMustIncludeTheLineTheCaseIsAbout() {
        assertCode(CaseErrorCode.MATCH_INVALID,
                () -> service.resolve(CASE_ID, 3, "alice", "why", List.of(11L), List.of(21L)));
    }

    @Test
    void sidesThatDoNotAddUpAreRefusedAndNothingIsWritten() {
        assertCode(CaseErrorCode.MATCH_INVALID,
                () -> service.resolve(CASE_ID, 3, "alice", "why", List.of(10L), List.of(21L)));
        assertNull(store.resolved);
    }

    @Test
    void aRepeatedLineInTheRequestIsRefused() {
        assertCode(CaseErrorCode.CASE_REQUEST_INVALID,
                () -> service.resolve(CASE_ID, 3, "alice", "why", List.of(10L), List.of(21L, 21L)));
    }

    @Test
    void aLineAlreadyClaimedByAnotherMatchIsReportedAsSuch() {
        store.outcome = ResolveOutcome.LINE_ALREADY_CLAIMED;

        assertCode(CaseErrorCode.MATCH_LINE_ALREADY_CLAIMED, this::resolveDefault);
    }

    @Test
    void aVersionConflictFoundByTheStoreIsReportedAsAConflict() {
        store.outcome = ResolveOutcome.VERSION_CONFLICT;

        assertCode(CaseErrorCode.CASE_VERSION_CONFLICT, this::resolveDefault);
    }

    @Test
    void unmatchOpensAReviewCaseRoutedToTheAnalysts() {
        store.active = Optional.of(new ActiveMatch(List.of(new ClaimedLine(5, line(Side.STATEMENT, 10, 3000)),
                new ClaimedLine(6, line(Side.LEDGER, 20, 3000))), CASE_ID));

        long id = service.unmatchCase(CASE_ID, "bob", "wrong payer");

        assertEquals(99, id);
        NewUnmatchCase opened = store.opened;
        assertEquals(CaseTeam.RECON_ANALYSTS, opened.team());
        assertEquals(CasePriority.P3, opened.priority());
        assertEquals("UNMATCH:5", opened.naturalKey());
        assertEquals(3000, opened.amountMinor());
        assertEquals("GBP", opened.currency());
        assertEquals(LocalDate.of(2026, 10, 28), opened.createdBusinessDate());
        assertEquals(5, opened.slaBusinessDays());
        assertEquals(BusinessCalendar.addBusinessDays(LocalDate.of(2026, 10, 28), 5), opened.dueBusinessDate());
    }

    @Test
    void aHighValueUnmatchGoesToTheSeniorAnalystsAtTopPriority() {
        store.active = Optional.of(new ActiveMatch(List.of(new ClaimedLine(5, line(Side.STATEMENT, 10, 5_000_000)),
                new ClaimedLine(6, line(Side.LEDGER, 20, 5_000_000))), null));

        service.unmatchRunMatch(1, 1, "bob", "false match");

        assertEquals(CaseTeam.SENIOR_ANALYST, store.opened.team());
        assertEquals(CasePriority.P1, store.opened.priority());
        assertEquals(1, store.opened.slaBusinessDays());
    }

    @Test
    void unmatchingSomethingWithNoActiveClaimsIsRefused() {
        assertCode(CaseErrorCode.NOTHING_TO_UNMATCH, () -> service.unmatchCase(CASE_ID, "bob", "why"));
        assertCode(CaseErrorCode.NOTHING_TO_UNMATCH, () -> service.unmatchRunMatch(1, 1, "bob", "why"));
    }

    @Test
    void anUnmatchThatLosesTheRaceIsRefused() {
        store.active = Optional.of(new ActiveMatch(List.of(new ClaimedLine(5, line(Side.STATEMENT, 10, 3000))), CASE_ID));
        store.unmatchResult = OptionalLong.empty();

        assertCode(CaseErrorCode.NOTHING_TO_UNMATCH, () -> service.unmatchCase(CASE_ID, "bob", "why"));
    }

    @Test
    void unmatchNeedsAReasonAndAnExistingCase() {
        assertCode(CaseErrorCode.CASE_REQUEST_INVALID, () -> service.unmatchCase(CASE_ID, "bob", ""));
        store.caseExists = false;
        assertCode(CaseErrorCode.CASE_NOT_FOUND, () -> service.unmatchCase(CASE_ID, "bob", "why"));
    }

    private void resolveDefault() {
        service.resolve(CASE_ID, 3, "alice", "why", List.of(10L), List.of(20L));
    }

    private static void assertCode(CaseErrorCode expected, Runnable action) {
        CaseRejectedException e = assertThrows(CaseRejectedException.class, action::run);
        assertEquals(expected, e.code());
    }

    private static MatchLine line(Side side, long seq, long amount) {
        return new MatchLine(side, seq, amount, "GBP");
    }

    private static final class FakeStore implements CaseMatchStore {
        boolean caseExists = true;
        CaseState state = new CaseState(CaseStatus.INVESTIGATING, 3);
        final List<MatchLine> lines = new ArrayList<>(List.of(
                line(Side.STATEMENT, 10, 3000), line(Side.STATEMENT, 11, 1000),
                line(Side.LEDGER, 20, 3000), line(Side.LEDGER, 21, 1000), line(Side.LEDGER, 22, 2000)));
        List<SubjectLine> subject = List.of(new SubjectLine(Side.STATEMENT, 10));
        ResolveOutcome outcome = ResolveOutcome.RESOLVED;
        Resolution resolved;
        Optional<ActiveMatch> active = Optional.empty();
        OptionalLong unmatchResult = OptionalLong.of(99);
        NewUnmatchCase opened;

        @Override
        public Optional<CaseState> findState(long caseId) {
            return caseExists ? Optional.of(state) : Optional.empty();
        }

        @Override
        public List<MatchLine> loadLines(List<Long> statementSeqs, List<Long> ledgerSeqs) {
            return lines.stream().filter(l -> (l.side() == Side.STATEMENT ? statementSeqs : ledgerSeqs)
                    .contains(l.seq())).toList();
        }

        @Override
        public List<SubjectLine> subjectLines(long caseId) {
            return subject;
        }

        @Override
        public ResolveOutcome resolve(Resolution resolution, Instant at) {
            if (outcome == ResolveOutcome.RESOLVED) {
                resolved = resolution;
            }
            return outcome;
        }

        @Override
        public Optional<ActiveMatch> findActiveMatchOfCase(long caseId) {
            return active;
        }

        @Override
        public Optional<ActiveMatch> findActiveMatchOfRunMatch(long runId, int matchOrdinal) {
            return active;
        }

        @Override
        public OptionalLong unmatch(ActiveMatch match, NewUnmatchCase newCase, String actor, String reason,
                Instant at) {
            opened = newCase;
            return unmatchResult;
        }
    }
}