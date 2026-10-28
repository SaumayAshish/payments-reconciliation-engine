package com.saumay.payments.application;

import com.saumay.payments.application.CaseMatchStore.ActiveMatch;
import com.saumay.payments.application.CaseMatchStore.CaseState;
import com.saumay.payments.application.CaseMatchStore.ClaimedLine;
import com.saumay.payments.application.CaseMatchStore.NewUnmatchCase;
import com.saumay.payments.application.CaseMatchStore.Resolution;
import com.saumay.payments.application.CaseMatchStore.SubjectLine;
import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.IllegalCaseTransitionException;
import com.saumay.payments.domain.cases.ManualMatchRejectedException;
import com.saumay.payments.domain.cases.ManualMatchRules;
import com.saumay.payments.domain.cases.MatchLine;
import com.saumay.payments.domain.cases.MatchLine.Side;
import com.saumay.payments.domain.cases.ResolutionType;
import com.saumay.payments.domain.cases.RoutingConfig;
import com.saumay.payments.domain.cases.RoutingRules;
import com.saumay.payments.domain.cases.RoutingRules.CasePlan;
import com.saumay.payments.domain.model.BusinessCalendar;
import com.saumay.payments.domain.money.CurrencyCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves a case with a manual match or split and reverses matches (FRD-12). Every rule that does not need the
 * database is checked here first; the store then writes the case, its timeline and the claims atomically, and the
 * claim ledger's own constraints are the last line of defence against a line being matched twice (ADR-0009).
 */
public final class CaseMatchService {

    private static final int MAX_NAME = 100;
    private static final int MAX_REASON = 1_000;

    private final CaseMatchStore store;
    private final RoutingRules rules;
    private final Clock clock;

    public CaseMatchService(CaseMatchStore store, RoutingConfig config, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        Objects.requireNonNull(config, "config");
        this.rules = new RoutingRules(config);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Resolves an INVESTIGATING case by matching the chosen lines. The lines must include the one the case is about,
     * share one currency and add up exactly; one side must be a single line.
     */
    public CaseState resolve(long caseId, long expectedVersion, String actor, String reason,
            List<Long> statementSeqs, List<Long> ledgerSeqs) {
        String by = requiredText(actor, MAX_NAME, "actor");
        String why = requiredText(reason, MAX_REASON, "reason");
        List<Long> statementIds = distinctSeqs(statementSeqs, "statementSeqs");
        List<Long> ledgerIds = distinctSeqs(ledgerSeqs, "ledgerSeqs");
        CaseState current = store.findState(caseId).orElseThrow(() -> notFound(caseId));
        if (current.version() != expectedVersion) {
            throw conflict(caseId);
        }
        requireResolvable(current.status());

        List<MatchLine> found = store.loadLines(statementIds, ledgerIds);
        List<MatchLine> statements = sideOf(found, Side.STATEMENT);
        List<MatchLine> ledger = sideOf(found, Side.LEDGER);
        if (statements.size() != statementIds.size() || ledger.size() != ledgerIds.size()) {
            throw new CaseRejectedException(CaseErrorCode.MATCH_LINE_NOT_FOUND, "One or more of the lines do not exist");
        }
        for (SubjectLine subject : store.subjectLines(caseId)) {
            List<Long> chosen = subject.side() == Side.STATEMENT ? statementIds : ledgerIds;
            if (!chosen.contains(subject.seq())) {
                throw new CaseRejectedException(CaseErrorCode.MATCH_INVALID,
                        "The match must include the line this case is about");
            }
        }
        ResolutionType type;
        try {
            type = ManualMatchRules.classify(statements, ledger);
        } catch (ManualMatchRejectedException e) {
            throw new CaseRejectedException(CaseErrorCode.MATCH_INVALID, e.getMessage());
        }

        List<MatchLine> all = new ArrayList<>(statements);
        all.addAll(ledger);
        switch (store.resolve(new Resolution(caseId, expectedVersion, type, by, why, all), clock.instant())) {
            case RESOLVED -> {
            }
            case VERSION_CONFLICT -> throw conflict(caseId);
            case LINE_ALREADY_CLAIMED -> throw new CaseRejectedException(CaseErrorCode.MATCH_LINE_ALREADY_CLAIMED,
                    "One or more of the lines already belong to an active match");
        }
        return store.findState(caseId).orElseThrow(() -> notFound(caseId));
    }

    /** Releases every claim of a resolved case and opens a new review case linked to it. Returns the new case id. */
    public long unmatchCase(long resolvedCaseId, String actor, String reason) {
        String by = requiredText(actor, MAX_NAME, "actor");
        String why = requiredText(reason, MAX_REASON, "reason");
        store.findState(resolvedCaseId).orElseThrow(() -> notFound(resolvedCaseId));
        return unmatch(store.findActiveMatchOfCase(resolvedCaseId), by, why);
    }

    /** Releases the claims of an auto-confirmed run match and opens a new review case. Returns the new case id. */
    public long unmatchRunMatch(long runId, int matchOrdinal, String actor, String reason) {
        String by = requiredText(actor, MAX_NAME, "actor");
        String why = requiredText(reason, MAX_REASON, "reason");
        return unmatch(store.findActiveMatchOfRunMatch(runId, matchOrdinal), by, why);
    }

    private long unmatch(Optional<ActiveMatch> found, String actor, String reason) {
        ActiveMatch match = found.orElseThrow(CaseMatchService::nothingToUnmatch);
        long statementTotal = 0;
        long ledgerTotal = 0;
        boolean hasStatement = false;
        for (ClaimedLine claimed : match.lines()) {
            if (claimed.line().side() == Side.STATEMENT) {
                statementTotal = Math.addExact(statementTotal, claimed.line().amountMinor());
                hasStatement = true;
            } else {
                ledgerTotal = Math.addExact(ledgerTotal, claimed.line().amountMinor());
            }
        }
        long amount = hasStatement ? statementTotal : ledgerTotal;
        String currency = match.lines().get(0).line().currency();
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate created = BusinessCalendar.rollForward(today);
        CasePlan plan = rules.plan(CaseKind.LOW_CONFIDENCE, amount, currencyOf(currency), created);
        long firstClaim = match.lines().stream().mapToLong(ClaimedLine::claimSeq).min().orElseThrow();
        NewUnmatchCase newCase = new NewUnmatchCase("UNMATCH:" + firstClaim, amount, currency,
                plan.routing().priority(), plan.routing().team(), plan.routing().ruleName(), created,
                plan.slaBusinessDays(), plan.dueBusinessDate());
        return store.unmatch(match, newCase, actor, reason, clock.instant())
                .orElseThrow(CaseMatchService::nothingToUnmatch);
    }

    private static void requireResolvable(CaseStatus status) {
        try {
            status.requireMoveTo(CaseStatus.RESOLVED);
        } catch (IllegalCaseTransitionException e) {
            throw new CaseRejectedException(CaseErrorCode.ILLEGAL_STATE_TRANSITION,
                    "A case can only be resolved while it is INVESTIGATING; this one is " + status);
        }
    }

    private static List<MatchLine> sideOf(List<MatchLine> lines, Side side) {
        return lines.stream().filter(line -> line.side() == side).toList();
    }

    private static List<Long> distinctSeqs(List<Long> seqs, String field) {
        if (seqs == null) {
            throw invalid(field + " is required");
        }
        Set<Long> seen = new TreeSet<>();
        for (Long seq : seqs) {
            if (seq == null || seq < 1) {
                throw invalid(field + " must hold positive ids");
            }
            if (!seen.add(seq)) {
                throw invalid(field + " must not repeat an id");
            }
        }
        return List.copyOf(seqs);
    }

    private static CurrencyCode currencyOf(String code) {
        return CurrencyCode.of(code);
    }

    private static String requiredText(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required");
        }
        String text = value.trim();
        if (text.length() > max) {
            throw invalid(field + " must be at most " + max + " characters");
        }
        return text;
    }

    private static CaseRejectedException nothingToUnmatch() {
        return new CaseRejectedException(CaseErrorCode.NOTHING_TO_UNMATCH, "There is no active match to unmatch");
    }

    private static CaseRejectedException notFound(long caseId) {
        return new CaseRejectedException(CaseErrorCode.CASE_NOT_FOUND, "No case exists with id " + caseId);
    }

    private static CaseRejectedException conflict(long caseId) {
        return new CaseRejectedException(CaseErrorCode.CASE_VERSION_CONFLICT,
                "Case " + caseId + " was changed by someone else; reload it and try again");
    }

    private static CaseRejectedException invalid(String message) {
        return new CaseRejectedException(CaseErrorCode.CASE_REQUEST_INVALID, message);
    }
}