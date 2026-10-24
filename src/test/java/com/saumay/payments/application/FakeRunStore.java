package com.saumay.payments.application;

import com.saumay.payments.matching.RunResult;
import com.saumay.payments.matching.RunSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** An in-memory {@link RunStore} that records what the service asked of it and can be told to fail. */
class FakeRunStore implements RunStore {

    final List<String> calls = new ArrayList<>();
    RunHeader header;
    RunInputs inputs;
    RuntimeException failOnLoad;
    RuntimeException failOnComplete;
    RuntimeException failOnFail;
    long recordedDuration = -1;

    @Override
    public RunHeader create(RunRequest request) {
        calls.add("create");
        header = new RunHeader(1, request.businessDate(), RunStatus.CREATED, request.config(), 99,
                request.statementIngestionIds(), Instant.EPOCH, null, null, null, null, null, null);
        return header;
    }

    @Override
    public RunInputs loadInputs(long runId) {
        calls.add("loadInputs");
        if (failOnLoad != null) {
            throw failOnLoad;
        }
        return inputs;
    }

    @Override
    public void markRunning(long runId) {
        calls.add("markRunning");
        header = with(RunStatus.RUNNING, null, null, null, null);
    }

    @Override
    public void complete(long runId, RunResult result, long durationMillis) {
        calls.add("complete");
        if (failOnComplete != null) {
            throw failOnComplete;
        }
        recordedDuration = durationMillis;
        header = with(RunStatus.COMPLETED, durationMillis, result.outputHash().hex(), result.summary(), null);
    }

    @Override
    public void fail(long runId, String reason) {
        calls.add("fail:" + reason);
        if (failOnFail != null) {
            throw failOnFail;
        }
        header = with(RunStatus.FAILED, null, null, null, reason);
    }

    @Override
    public Optional<RunHeader> find(long runId) {
        return Optional.ofNullable(header);
    }

    private RunHeader with(RunStatus status, Long duration, String hash, RunSummary summary, String failure) {
        return new RunHeader(header.id(), header.businessDate(), status, header.config(), header.ledgerCutoffSeq(),
                header.statementIngestionIds(), header.createdAt(), Instant.EPOCH, Instant.EPOCH, duration, hash,
                summary, failure);
    }
}