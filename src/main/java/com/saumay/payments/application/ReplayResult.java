package com.saumay.payments.application;

import java.util.Objects;

/**
 * The outcome of replaying a completed run (FRD-10): the hash stored when the run completed, the hash recomputed from
 * the same stored inputs and configuration, and whether they agree. Replaying never changes the stored run.
 */
public record ReplayResult(long runId, String storedHash, String replayedHash) {

    public ReplayResult {
        Objects.requireNonNull(storedHash, "storedHash");
        Objects.requireNonNull(replayedHash, "replayedHash");
    }

    public boolean identical() {
        return storedHash.equals(replayedHash);
    }
}