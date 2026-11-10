package com.saumay.payments.application;

import com.saumay.payments.domain.approval.ApprovalThreshold;
import java.time.Instant;
import java.util.Objects;

/** Reads and appends complete, versioned approval-threshold snapshots. */
public interface ApprovalConfigurationStore {

    /** Returns version zero and the built-in defaults when no version is stored. */
    Snapshot current();

    /** Appends a complete configuration and its audit event in one transaction. */
    Snapshot append(ApprovalThreshold threshold, String actor, Instant at);

    record Snapshot(long version, ApprovalThreshold threshold) {
        public Snapshot {
            if (version < 0) {
                throw new IllegalArgumentException("version must not be negative");
            }
            Objects.requireNonNull(threshold, "threshold");
        }
    }
}