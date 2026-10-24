package com.saumay.payments.matching;

import com.saumay.payments.domain.model.BusinessCalendar;
import com.saumay.payments.domain.model.LineSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns what the pipeline could not confirm into exceptions (FRD-05, FRD-06). Pure and order-independent: every
 * list it builds is sorted, and the hash maps it uses are only looked up.
 *
 * <ul>
 *   <li>A match below the auto threshold is {@code LOW_CONFIDENCE}, carrying the proposed match itself.</li>
 *   <li>An ambiguous group is {@code AMBIGUOUS_TIE}, with the first three tied ledger lines as candidates.</li>
 *   <li>An unmatched statement line is {@code CURRENCY_MISMATCH} or {@code AMOUNT_MISMATCH} when an unmatched
 *       ledger entry with the same reference looks like it, otherwise {@code MISSING_LEDGER}.</li>
 *   <li>An unmatched ledger entry older than the lookback window is {@code MISSING_STATEMENT}; a younger one
 *       is carried forward to the next run and raises nothing yet (Q-05).</li>
 * </ul>
 */
public final class ExceptionClassifier {

    static final Comparator<RunException> ORDER = Comparator
            .comparing((RunException e) -> e.kind().name())
            .thenComparing(e -> e.subjects().get(0).key());

    private ExceptionClassifier() {
    }

    /** What a run raised, and the ledger entries it carries forward to the next run without raising anything. */
    public record Classification(List<RunException> exceptions, List<MatchableLine> carriedForward) {

        public Classification {
            exceptions = List.copyOf(exceptions);
            carriedForward = List.copyOf(carriedForward);
        }
    }

    public static Classification classify(PipelineResult result, LocalDate businessDate, MatchingConfig config) {
        List<RunException> exceptions = new ArrayList<>();
        for (Match match : result.matches()) {
            if (match.band() != MatchBand.AUTO_CONFIRM) {
                exceptions.add(new RunException(ExceptionKind.LOW_CONFIDENCE,
                        subjects(match.statementSide(), match.ledgerSide()), match.reason(),
                        "tier=" + match.tier() + " score=" + match.score() + " band=" + match.band(), List.of()));
            }
        }
        for (AmbiguousGroup group : result.ambiguous()) {
            exceptions.add(new RunException(ExceptionKind.AMBIGUOUS_TIE,
                    subjects(group.statementSide(), group.ledgerSide()), AmbiguousGroup.REASON,
                    "tier=" + group.tier() + " score=" + group.score(), firstThree(group.ledgerSide())));
        }
        unmatchedStatements(result, config, exceptions);
        List<MatchableLine> carried = new ArrayList<>();
        unmatchedLedger(result, businessDate, config, exceptions, carried);
        exceptions.sort(ORDER);
        return new Classification(exceptions, carried);
    }

    private static void unmatchedStatements(PipelineResult result, MatchingConfig config,
                                            List<RunException> out) {
        Map<String, List<MatchableLine>> byReference = new HashMap<>();
        for (MatchableLine entry : result.unmatchedLedger()) {
            byReference.computeIfAbsent(entry.line().referenceNorm(), k -> new ArrayList<>()).add(entry);
        }
        for (MatchableLine statement : result.unmatchedStatements()) {
            List<MatchableLine> sameReference = byReference.getOrDefault(statement.line().referenceNorm(),
                    List.of());
            List<MatchableLine> currency = new ArrayList<>();
            List<MatchableLine> amount = new ArrayList<>();
            for (MatchableLine entry : sameReference) {
                if (isCurrencyMismatch(statement, entry, config)) {
                    currency.add(entry);
                } else if (isAmountMismatch(statement, entry)) {
                    amount.add(entry);
                }
            }
            if (!currency.isEmpty()) {
                currency.sort(MatchableLine.CANONICAL_ORDER);
                MatchableLine best = currency.get(0);
                out.add(new RunException(ExceptionKind.CURRENCY_MISMATCH, subjects(List.of(statement), List.of()),
                        "CURRENCY_MISMATCH: same reference and amount, different currency",
                        "statementCurrency=" + statement.line().amount().currency() + " ledgerCurrency="
                                + best.line().amount().currency(), firstThree(currency)));
            } else if (!amount.isEmpty()) {
                amount.sort(MatchableLine.CANONICAL_ORDER);
                MatchableLine best = amount.get(0);
                long delta = best.line().amount().minorUnits() - statement.line().amount().minorUnits();
                out.add(new RunException(ExceptionKind.AMOUNT_MISMATCH, subjects(List.of(statement), List.of()),
                        "AMOUNT_MISMATCH: same reference, account, currency and value date, different amount",
                        "deltaMinor=" + delta, firstThree(amount)));
            } else {
                out.add(new RunException(ExceptionKind.MISSING_LEDGER, subjects(List.of(statement), List.of()),
                        "MISSING_LEDGER: no ledger entry above the review threshold", "", List.of()));
            }
        }
    }

    private static boolean isCurrencyMismatch(MatchableLine statement, MatchableLine entry, MatchingConfig config) {
        return !statement.line().amount().currency().equals(entry.line().amount().currency())
                && statement.line().amount().minorUnits() == entry.line().amount().minorUnits()
                && BusinessCalendar.businessDaysBetween(statement.line().valueDate(), entry.line().valueDate())
                <= config.dateWindowBusinessDays();
    }

    private static boolean isAmountMismatch(MatchableLine statement, MatchableLine entry) {
        return statement.line().account().equals(entry.line().account())
                && statement.line().amount().currency().equals(entry.line().amount().currency())
                && statement.line().valueDate().equals(entry.line().valueDate())
                && statement.line().amount().minorUnits() != entry.line().amount().minorUnits();
    }

    private static void unmatchedLedger(PipelineResult result, LocalDate businessDate, MatchingConfig config,
                                        List<RunException> out, List<MatchableLine> carried) {
        for (MatchableLine entry : result.unmatchedLedger()) {
            if (isOutsideLookback(entry, businessDate, config)) {
                out.add(new RunException(ExceptionKind.MISSING_STATEMENT, subjects(List.of(), List.of(entry)),
                        "MISSING_STATEMENT: no statement line within the lookback window", "", List.of()));
            } else {
                carried.add(entry);
            }
        }
        carried.sort(MatchableLine.CANONICAL_ORDER);
    }

    /** True when the entry is older than the lookback window; a future-dated entry is carried forward. */
    static boolean isOutsideLookback(MatchableLine entry, LocalDate businessDate, MatchingConfig config) {
        LocalDate valueDate = entry.line().valueDate();
        return valueDate.isBefore(businessDate)
                && BusinessCalendar.businessDaysBetween(valueDate, businessDate) > config.lookbackBusinessDays();
    }

    private static List<MatchableLine> subjects(List<MatchableLine> statementSide, List<MatchableLine> ledgerSide) {
        List<MatchableLine> statements = new ArrayList<>(statementSide);
        statements.sort(MatchableLine.CANONICAL_ORDER);
        List<MatchableLine> ledger = new ArrayList<>(ledgerSide);
        ledger.sort(MatchableLine.CANONICAL_ORDER);
        List<MatchableLine> all = new ArrayList<>(statements);
        all.addAll(ledger);
        return all;
    }

    private static List<MatchableLine> firstThree(List<MatchableLine> lines) {
        List<MatchableLine> sorted = new ArrayList<>(lines);
        sorted.sort(MatchableLine.CANONICAL_ORDER);
        return sorted.subList(0, Math.min(RunException.MAX_CANDIDATES, sorted.size()));
    }

    /** The side of a subject line, for output formatting. */
    static String sideCode(MatchableLine line) {
        return line.line().source() == LineSource.STATEMENT ? "S" : "L";
    }
}