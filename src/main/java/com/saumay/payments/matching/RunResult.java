package com.saumay.payments.matching;

import com.saumay.payments.domain.model.ContentHash;
import java.util.Objects;

/** Everything one run decided, with its canonical text and hash. Replay recomputes this and compares hashes. */
public record RunResult(PipelineResult pipeline, ExceptionClassifier.Classification classification,
                        String canonicalText, ContentHash outputHash, RunSummary summary) {

    public RunResult {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(canonicalText, "canonicalText");
        Objects.requireNonNull(outputHash, "outputHash");
        Objects.requireNonNull(summary, "summary");
    }
}