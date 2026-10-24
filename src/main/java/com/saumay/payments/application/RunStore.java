package com.saumay.payments.application;

import com.saumay.payments.matching.RunResult;
import java.util.Optional;

/**
 * Persistence port for reconciliation runs. The adapter lives in the infrastructure layer.
 *
 * <p>Life of a run: {@link #create} (short transaction that fixes the inputs) then {@link #markRunning}, then
 * either {@link #complete} (result rows and the COMPLETED status together, atomically) or {@link #fail}.
 */
public interface RunStore {

    /**
     * Fixes the run's inputs and stores it as CREATED. Takes the ledger cut-off while no ingestion is half
     * committed (ADR-0003 amendment), so every ledger entry at or below the cut-off is visible.
     *
     * @throws RunRejectedException if a named ingestion does not exist or is not a statement
     */
    RunHeader create(RunRequest request);

    /** The lines the run reconciles: its statement ingestions plus ledger entries up to its cut-off. */
    RunInputs loadInputs(long runId);

    /** CREATED to RUNNING. */
    void markRunning(long runId);

    /** Writes every match, exception and note, then RUNNING to COMPLETED with the output hash, in one transaction. */
    void complete(long runId, RunResult result, long durationMillis);

    /** CREATED or RUNNING to FAILED, with a reason safe to show to a client. */
    void fail(long runId, String reason);

    Optional<RunHeader> find(long runId);
}