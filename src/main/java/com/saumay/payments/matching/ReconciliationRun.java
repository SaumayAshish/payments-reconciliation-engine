package com.saumay.payments.matching;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The whole engine as one pure function: {@code (statements, ledger, businessDate, config) -> RunResult}. The same
 * inputs and configuration always give the same canonical text and hash, whatever order the lines arrive in
 * and however many threads call it at once. Replay is nothing more than calling it again with the stored inputs
 * and comparing hashes (FRD-10).
 */
public final class ReconciliationRun {

    private ReconciliationRun() {
    }

    public static RunResult compute(List<MatchableLine> statements, List<MatchableLine> ledger,
                                    LocalDate businessDate, MatchingConfig config) {
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(config, "config");
        PipelineResult pipeline = MatchingPipeline.run(statements, ledger, config);
        ExceptionClassifier.Classification classification = ExceptionClassifier.classify(pipeline, businessDate,
                config);
        String text = CanonicalOutput.text(pipeline, classification);

        int auto = 0;
        for (Match match : pipeline.matches()) {
            if (match.band() == MatchBand.AUTO_CONFIRM) {
                auto++;
            }
        }
        int truncations = 0;
        for (String note : pipeline.notes()) {
            if (note.startsWith(OneToManyTier.TRUNCATED_NOTE)) {
                truncations++;
            }
        }
        RunSummary summary = new RunSummary(statements.size(), ledger.size(), pipeline.matches().size(), auto,
                pipeline.matches().size() - auto, pipeline.ambiguous().size(), classification.exceptions().size(),
                classification.carriedForward().size(), truncations);
        return new RunResult(pipeline, classification, text, CanonicalOutput.hash(text), summary);
    }

    /** Replay: recompute from the stored inputs and report whether the hash is the one stored with the run. */
    public static boolean replayMatches(List<MatchableLine> statements, List<MatchableLine> ledger,
                                        LocalDate businessDate, MatchingConfig config, String storedOutputHashHex) {
        return compute(statements, ledger, businessDate, config).outputHash().hex().equals(storedOutputHashHex);
    }
}