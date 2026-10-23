package com.saumay.payments.matching;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the four tiers in order (FRD-04). A line leaves the pipeline at the first tier that claims it, whether as
 * a match or as part of an ambiguous group, so a line is never reported twice. The function is pure: the same
 * lines and configuration always give the same result, whatever order the lines arrive in.
 */
public final class MatchingPipeline {

    private MatchingPipeline() {
    }

    public static PipelineResult run(List<MatchableLine> statements, List<MatchableLine> ledger,
                                     MatchingConfig config) {
        List<Match> matches = new ArrayList<>();
        List<AmbiguousGroup> ambiguous = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        TierOutcome exact = PairResolver.resolve(statements, ledger, ExactTier.candidates(statements, ledger),
                config);
        absorb(exact, matches, ambiguous, notes);

        TierOutcome tolerance = PairResolver.resolve(exact.remainingStatements(), exact.remainingLedger(),
                ToleranceTier.candidates(exact.remainingStatements(), exact.remainingLedger(), config), config);
        absorb(tolerance, matches, ambiguous, notes);

        TierOutcome fuzzy = PairResolver.resolve(tolerance.remainingStatements(), tolerance.remainingLedger(),
                FuzzyTier.candidates(tolerance.remainingStatements(), tolerance.remainingLedger(), config),
                config);
        absorb(fuzzy, matches, ambiguous, notes);

        TierOutcome oneToMany = OneToManyTier.run(fuzzy.remainingStatements(), fuzzy.remainingLedger(), config);
        absorb(oneToMany, matches, ambiguous, notes);

        return new PipelineResult(matches, ambiguous, oneToMany.remainingStatements(),
                oneToMany.remainingLedger(), notes);
    }

    private static void absorb(TierOutcome outcome, List<Match> matches, List<AmbiguousGroup> ambiguous,
                               List<String> notes) {
        matches.addAll(outcome.matches());
        ambiguous.addAll(outcome.ambiguous());
        notes.addAll(outcome.notes());
    }
}