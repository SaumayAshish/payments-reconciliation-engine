package com.saumay.payments.application;

import com.saumay.payments.matching.ReconciliationRun;
import com.saumay.payments.matching.RunResult;
import com.saumay.payments.matching.RunSummary;
import java.time.Clock;
import java.util.Objects;

/**
 * Runs and replays reconciliations (FRD-10). All matching is done by the pure {@link ReconciliationRun}; this class
 * only sequences the steps around it and keeps the stored run honest:
 *
 * <ol>
 *   <li>the run's inputs are fixed in a short transaction ({@link RunStore#create});</li>
 *   <li>matching happens outside any transaction or lock;</li>
 *   <li>the result and the COMPLETED status are written together, or the run is marked FAILED.</li>
 * </ol>
 *
 * <p>A run is never left half-way: whatever goes wrong after creation ends in FAILED with a generic reason. The
 * original exception is rethrown so the API layer can log it with its correlation id.
 */
public final class ReconciliationRunService {

    static final String FAILURE_REASON = "The run failed unexpectedly; see the server log";

    private final RunStore store;
    private final Clock clock;

    public ReconciliationRunService(RunStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Creates, executes and completes a run, and returns it as stored. */
    public RunHeader start(RunRequest request) {
        RunHeader created = store.create(request);
        long runId = created.id();
        try {
            store.markRunning(runId);
            RunInputs inputs = store.loadInputs(runId);
            long begin = clock.millis();
            RunResult result = ReconciliationRun.compute(inputs.statements(), inputs.ledger(),
                    inputs.businessDate(), inputs.config());
            store.complete(runId, result, clock.millis() - begin);
        } catch (RuntimeException e) {
            markFailed(runId, e);
            throw e;
        }
        return store.find(runId).orElseThrow(() -> new IllegalStateException("A completed run is missing"));
    }

    /**
     * Recomputes a completed run from its stored inputs and configuration and compares the hash with the one stored
     * when it completed.
     *
     * @throws RunRejectedException RUN_NOT_FOUND, RUN_NOT_COMPLETED, or REPLAY_INPUTS_UNAVAILABLE when the stored
     *                              inputs no longer add up to what the run saw
     */
    public ReplayResult replay(long runId) {
        RunHeader run = store.find(runId).orElseThrow(
                () -> new RunRejectedException(RunErrorCode.RUN_NOT_FOUND, "No run exists with id " + runId));
        if (run.status() != RunStatus.COMPLETED) {
            throw new RunRejectedException(RunErrorCode.RUN_NOT_COMPLETED,
                    "Only a completed run can be replayed; run " + runId + " is " + run.status());
        }
        RunInputs inputs = store.loadInputs(runId);
        RunSummary seen = run.summary();
        if (inputs.statements().size() != seen.statementLines() || inputs.ledger().size() != seen.ledgerEntries()) {
            throw new RunRejectedException(RunErrorCode.REPLAY_INPUTS_UNAVAILABLE,
                    "The stored inputs of run " + runId + " no longer match what the run reconciled");
        }
        RunResult again = ReconciliationRun.compute(inputs.statements(), inputs.ledger(), inputs.businessDate(),
                inputs.config());
        return new ReplayResult(runId, run.outputHash(), again.outputHash().hex());
    }

    private void markFailed(long runId, RuntimeException original) {
        try {
            store.fail(runId, FAILURE_REASON);
        } catch (RuntimeException secondary) {
            original.addSuppressed(secondary);
        }
    }
}