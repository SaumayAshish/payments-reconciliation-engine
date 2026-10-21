package com.saumay.payments.matching;

import com.saumay.payments.domain.model.BusinessCalendar;
import com.saumay.payments.domain.model.LineKey;
import com.saumay.payments.domain.money.CurrencyCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tier 4 (FRD-04): one line equals the exact sum of several lines on the other side, as in a batch settlement,
 * in both directions. All lines in a decomposition share account, currency and sign, every component is smaller
 * than the single line, and every value date is within the configured window of the single line's.
 *
 * <p>The search is bounded. For each single line only the best {@code maxOneToManyCandidates} eligible lines
 * are considered, nearest value date first and then canonical order, and a truncation is recorded as a note
 * (AC-04.5). All subsets of those candidates are tried by depth-first search, at most 2^8 for the default.
 *
 * <p>Choosing which lines make up a batch is itself the decision a human must take, so any two proposals that
 * share a line, whatever their scores, are held back together as an ambiguous group (AC-04.6); only a proposal
 * whose lines appear in no other proposal becomes a match.
 *
 * <p>Score: the tier floor, plus a bonus when every component carries the single line's own normalised
 * reference (a batch reference), plus a bonus when every value date is identical, capped at the tier ceiling.
 */
public final class OneToManyTier {

    public static final String TRUNCATED_NOTE = "TIER4_CANDIDATES_TRUNCATED";

    static final int BATCH_REFERENCE_BONUS_MILLI = 70;
    static final int SAME_DATE_BONUS_MILLI = 30;

    private record Bucket(String account, CurrencyCode currency, int sign) {

        static Bucket of(MatchableLine line) {
            return new Bucket(line.line().account(), line.line().amount().currency(),
                    line.line().amount().signum());
        }
    }

    private record Candidate(MatchableLine line, int distance, long magnitude) {
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingInt(Candidate::distance)
            .thenComparing(Candidate::line, MatchableLine.CANONICAL_ORDER);

    private OneToManyTier() {
    }

    public static TierOutcome run(List<MatchableLine> statements, List<MatchableLine> ledger,
                                  MatchingConfig config) {
        List<String> notes = new ArrayList<>();
        List<Proposal> proposals = proposals(statements, ledger, config, notes);
        return resolve(statements, ledger, proposals, config, notes);
    }

    static List<Proposal> proposals(List<MatchableLine> statements, List<MatchableLine> ledger,
                                    MatchingConfig config, List<String> notes) {
        List<Proposal> all = new ArrayList<>();
        propose(statements, ledger, true, config, all, notes);
        propose(ledger, statements, false, config, all, notes);
        return all;
    }

    private static void propose(List<MatchableLine> singles, List<MatchableLine> pool, boolean singleIsStatement,
                                MatchingConfig config, List<Proposal> out, List<String> notes) {
        List<MatchableLine> orderedPool = new ArrayList<>(pool);
        orderedPool.sort(MatchableLine.CANONICAL_ORDER);
        Map<Bucket, List<MatchableLine>> buckets = new HashMap<>();
        for (MatchableLine line : orderedPool) {
            buckets.computeIfAbsent(Bucket.of(line), k -> new ArrayList<>()).add(line);
        }
        List<MatchableLine> orderedSingles = new ArrayList<>(singles);
        orderedSingles.sort(MatchableLine.CANONICAL_ORDER);

        for (MatchableLine single : orderedSingles) {
            List<MatchableLine> bucket = buckets.get(Bucket.of(single));
            if (bucket == null) {
                continue;
            }
            List<Candidate> eligible = eligible(single, bucket, config);
            if (eligible.size() > config.maxOneToManyCandidates()) {
                notes.add(TRUNCATED_NOTE + ": " + (singleIsStatement ? "statement " : "ledger ") + single.key()
                        + " had " + eligible.size() + " eligible candidates, considered "
                        + config.maxOneToManyCandidates());
                eligible = eligible.subList(0, config.maxOneToManyCandidates());
            }
            List<List<MatchableLine>> solutions = new ArrayList<>();
            search(eligible, 0, single.line().amount().abs().minorUnits(), new ArrayList<>(), solutions);
            for (List<MatchableLine> components : solutions) {
                out.add(proposal(single, singleIsStatement, components));
            }
        }
    }

    /** Lines in the same bucket, strictly smaller than the single line and inside the date window, best first. */
    private static List<Candidate> eligible(MatchableLine single, List<MatchableLine> bucket,
                                            MatchingConfig config) {
        int window = config.dateWindowBusinessDays();
        // Any date within `window` business days is within 2 * window + 4 calendar days (weekends and rolls).
        int calendarSpan = 2 * window + 4;
        LocalDate date = single.line().valueDate();
        long target = single.line().amount().abs().minorUnits();
        List<Candidate> result = new ArrayList<>();
        for (int i = lowerBound(bucket, date.minusDays(calendarSpan)); i < bucket.size(); i++) {
            MatchableLine line = bucket.get(i);
            if (line.line().valueDate().isAfter(date.plusDays(calendarSpan))) {
                break;
            }
            long magnitude = line.line().amount().abs().minorUnits();
            int distance = BusinessCalendar.businessDaysBetween(date, line.line().valueDate());
            if (magnitude < target && distance <= window) {
                result.add(new Candidate(line, distance, magnitude));
            }
        }
        result.sort(CANDIDATE_ORDER);
        return result;
    }

    private static int lowerBound(List<MatchableLine> sorted, LocalDate from) {
        int low = 0;
        int high = sorted.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted.get(middle).line().valueDate().isBefore(from)) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /**
     * Depth-first search over subsets in candidate order. The remaining amount only ever shrinks, so a candidate
     * larger than what remains is skipped; subtracting rather than adding also rules out overflow.
     */
    private static void search(List<Candidate> candidates, int start, long remaining, List<MatchableLine> chosen,
                               List<List<MatchableLine>> solutions) {
        if (remaining == 0) {
            solutions.add(List.copyOf(chosen));
            return;
        }
        for (int i = start; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (candidate.magnitude() > remaining) {
                continue;
            }
            chosen.add(candidate.line());
            search(candidates, i + 1, remaining - candidate.magnitude(), chosen, solutions);
            chosen.remove(chosen.size() - 1);
        }
    }

    private static Proposal proposal(MatchableLine single, boolean singleIsStatement,
                                     List<MatchableLine> components) {
        List<MatchableLine> ordered = new ArrayList<>(components);
        ordered.sort(MatchableLine.CANONICAL_ORDER);
        boolean batchReference = true;
        boolean sameDate = true;
        for (MatchableLine component : ordered) {
            batchReference &= component.line().referenceNorm().equals(single.line().referenceNorm());
            sameDate &= component.line().valueDate().equals(single.line().valueDate());
        }
        int milli = MatchTier.ONE_TO_MANY.floorMilli() + (batchReference ? BATCH_REFERENCE_BONUS_MILLI : 0)
                + (sameDate ? SAME_DATE_BONUS_MILLI : 0);
        MatchScore score = MatchScore.ofMilli(Math.min(milli, MatchTier.ONE_TO_MANY.ceilingMilli()));
        String reason = "ONE_TO_MANY: " + (singleIsStatement ? "statement line equals the sum of "
                + ordered.size() + " ledger entries" : "ledger entry equals the sum of " + ordered.size()
                + " statement lines") + (batchReference ? "; batch reference shared" : "")
                + (sameDate ? "; value dates equal" : "");
        return new Proposal(single, singleIsStatement, ordered, score, reason);
    }

    private static TierOutcome resolve(List<MatchableLine> statements, List<MatchableLine> ledger,
                                       List<Proposal> proposals, MatchingConfig config, List<String> notes) {
        LineClusters clusters = new LineClusters();
        for (Proposal proposal : proposals) {
            LineClusters.Node anchor = anchor(proposal);
            for (MatchableLine line : proposal.components()) {
                clusters.union(anchor, proposal.singleIsStatement() ? LineClusters.Node.ledger(line)
                        : LineClusters.Node.statement(line));
            }
        }
        Map<LineClusters.Node, List<Proposal>> byCluster = new LinkedHashMap<>();
        for (Proposal proposal : proposals) {
            byCluster.computeIfAbsent(clusters.find(anchor(proposal)), k -> new ArrayList<>()).add(proposal);
        }

        List<Match> matches = new ArrayList<>();
        List<AmbiguousGroup> ambiguous = new ArrayList<>();
        Set<LineKey> takenStatements = new HashSet<>();
        Set<LineKey> takenLedger = new HashSet<>();
        for (List<Proposal> cluster : byCluster.values()) {
            Map<LineKey, MatchableLine> statementLines = new LinkedHashMap<>();
            Map<LineKey, MatchableLine> ledgerLines = new LinkedHashMap<>();
            MatchScore best = cluster.get(0).score();
            for (Proposal proposal : cluster) {
                for (MatchableLine line : proposal.statementSide()) {
                    statementLines.putIfAbsent(line.key(), line);
                }
                for (MatchableLine line : proposal.ledgerSide()) {
                    ledgerLines.putIfAbsent(line.key(), line);
                }
                if (proposal.score().compareTo(best) > 0) {
                    best = proposal.score();
                }
            }
            takenStatements.addAll(statementLines.keySet());
            takenLedger.addAll(ledgerLines.keySet());
            List<MatchableLine> statementSide = sorted(statementLines);
            List<MatchableLine> ledgerSide = sorted(ledgerLines);
            if (cluster.size() == 1) {
                Proposal only = cluster.get(0);
                matches.add(new Match(MatchTier.ONE_TO_MANY, only.score(), config.classify(only.score()),
                        only.reason(), statementSide, ledgerSide));
            } else {
                ambiguous.add(new AmbiguousGroup(MatchTier.ONE_TO_MANY, best, statementSide, ledgerSide));
            }
        }
        matches.sort(Comparator.comparing(Match::score, Comparator.<MatchScore>reverseOrder())
                .thenComparing(m -> m.statementSide().get(0), MatchableLine.CANONICAL_ORDER)
                .thenComparing(m -> m.ledgerSide().get(0), MatchableLine.CANONICAL_ORDER));
        ambiguous.sort(Comparator.comparing(AmbiguousGroup::score, Comparator.<MatchScore>reverseOrder())
                .thenComparing(g -> g.statementSide().get(0), MatchableLine.CANONICAL_ORDER)
                .thenComparing(g -> g.ledgerSide().get(0), MatchableLine.CANONICAL_ORDER));
        return new TierOutcome(matches, ambiguous, PairResolver.remaining(statements, takenStatements),
                PairResolver.remaining(ledger, takenLedger), notes);
    }

    private static LineClusters.Node anchor(Proposal proposal) {
        return proposal.singleIsStatement() ? LineClusters.Node.statement(proposal.single())
                : LineClusters.Node.ledger(proposal.single());
    }

    private static List<MatchableLine> sorted(Map<LineKey, MatchableLine> lines) {
        List<MatchableLine> result = new ArrayList<>(lines.values());
        result.sort(MatchableLine.CANONICAL_ORDER);
        return result;
    }
}