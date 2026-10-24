package com.saumay.payments.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.model.CanonicalLine;
import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineKey;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.domain.money.CurrencyCode;
import com.saumay.payments.domain.money.Money;
import com.saumay.payments.matching.MatchableLine;
import com.saumay.payments.matching.MatchingConfig;
import com.saumay.payments.matching.ReconciliationRun;
import com.saumay.payments.matching.RunResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconciliationRunServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 9);
    private static final MatchingConfig CONFIG = MatchingConfig.defaults();

    /** A clock that moves 5 ms every time it is read, so a measured duration is exactly 5. */
    private static final class TickingClock extends Clock {
        private long now;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            now += 5;
            return Instant.ofEpochMilli(now);
        }
    }

    private static MatchableLine line(LineSource source, long seq, String reference, long minor) {
        ContentHash file = ContentHash.ofUtf8(source.name());
        CanonicalLine canonical = new CanonicalLine(source, "ACC-1", Money.of(minor, CurrencyCode.of("GBP")), DATE,
                null, reference, reference.replace("-", ""), "Acme Ltd", "ACME", "payment",
                LineKey.of(file, (int) seq));
        return new MatchableLine(seq, canonical);
    }

    private static FakeRunStore storeWith(List<MatchableLine> statements, List<MatchableLine> ledger) {
        FakeRunStore store = new FakeRunStore();
        store.inputs = new RunInputs(1, DATE, CONFIG, statements, ledger);
        return store;
    }

    private static List<MatchableLine> someStatements() {
        return List.of(line(LineSource.STATEMENT, 1, "REF-1", 1000), line(LineSource.STATEMENT, 2, "REF-2", 2000));
    }

    private static List<MatchableLine> someLedger() {
        return List.of(line(LineSource.LEDGER, 1, "REF-1", 1000), line(LineSource.LEDGER, 2, "REF-2", 2000));
    }

    private static RunRequest request() {
        return new RunRequest(DATE, List.of(1L), CONFIG);
    }

    @Test
    void aRunIsCreatedStartedMatchedAndCompletedInThatOrder() {
        FakeRunStore store = storeWith(someStatements(), someLedger());
        ReconciliationRunService service = new ReconciliationRunService(store, new TickingClock());

        RunHeader run = service.start(request());

        assertEquals(List.of("create", "markRunning", "loadInputs", "complete"), store.calls);
        assertEquals(RunStatus.COMPLETED, run.status());
        RunResult expected = ReconciliationRun.compute(someStatements(), someLedger(), DATE, CONFIG);
        assertEquals(expected.outputHash().hex(), run.outputHash());
        assertEquals(expected.summary(), run.summary());
        assertEquals(5L, store.recordedDuration);
    }

    @Test
    void aRunRefusedAtCreationLeavesNothingToMarkOrFail() {
        FakeRunStore store = new FakeRunStore() {
            @Override
            public RunHeader create(RunRequest request) {
                calls.add("create");
                throw new RunRejectedException(RunErrorCode.UNKNOWN_INGESTION, "No ingestion exists with id 1");
            }
        };
        ReconciliationRunService service = new ReconciliationRunService(store, new TickingClock());

        RunRejectedException refused = assertThrows(RunRejectedException.class, () -> service.start(request()));

        assertEquals(RunErrorCode.UNKNOWN_INGESTION, refused.code());
        assertEquals(List.of("create"), store.calls);
    }

    @Test
    void aFailureAfterCreationMarksTheRunFailedWithAGenericReasonAndRethrows() {
        FakeRunStore store = storeWith(someStatements(), someLedger());
        IllegalStateException boom = new IllegalStateException("connection to 10.0.0.5 refused, password=hunter2");
        store.failOnLoad = boom;
        ReconciliationRunService service = new ReconciliationRunService(store, new TickingClock());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> service.start(request()));

        assertSame(boom, thrown);
        assertEquals(RunStatus.FAILED, store.header.status());
        assertEquals(ReconciliationRunService.FAILURE_REASON, store.header.failureReason());
        assertFalse(store.header.failureReason().contains("hunter2"));
        assertEquals("fail:" + ReconciliationRunService.FAILURE_REASON, store.calls.get(store.calls.size() - 1));
    }

    @Test
    void aFailureWhileCompletingMarksTheRunFailed() {
        FakeRunStore store = storeWith(someStatements(), someLedger());
        store.failOnComplete = new IllegalStateException("insert failed");
        ReconciliationRunService service = new ReconciliationRunService(store, new TickingClock());

        assertThrows(IllegalStateException.class, () -> service.start(request()));

        assertEquals(RunStatus.FAILED, store.header.status());
    }

    @Test
    void ifMarkingFailedAlsoFailsTheOriginalErrorIsStillTheOneThrown() {
        FakeRunStore store = storeWith(someStatements(), someLedger());
        IllegalStateException original = new IllegalStateException("original");
        store.failOnLoad = original;
        store.failOnFail = new IllegalStateException("database is gone");
        ReconciliationRunService service = new ReconciliationRunService(store, new TickingClock());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> service.start(request()));

        assertSame(original, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals("database is gone", thrown.getSuppressed()[0].getMessage());
    }

    @Test
    void replayingACompletedRunGivesTheSameHash() {
        FakeRunStore store = storeWith(someStatements(), someLedger());
        ReconciliationRunService service = new ReconciliationRunService(store, new TickingClock());
        service.start(request());

        ReplayResult replay = service.replay(1);

        assertTrue(replay.identical());
        assertEquals(store.header.outputHash(), replay.storedHash());
        assertEquals(replay.storedHash(), replay.replayedHash());
    }

    @Test
    void replayDetectsInputsThatChangedWithoutChangingTheirCount() {
        FakeRunStore store = storeWith(someStatements(), someLedger());
        ReconciliationRunService service = new ReconciliationRunService(store, new TickingClock());
        service.start(request());
        List<MatchableLine> tampered = new ArrayList<>(someLedger());
        tampered.set(1, line(LineSource.LEDGER, 2, "REF-2", 2001));
        store.inputs = new RunInputs(1, DATE, CONFIG, someStatements(), tampered);

        ReplayResult replay = service.replay(1);

        assertFalse(replay.identical());
        assertEquals(64, replay.replayedHash().length());
    }

    @Test
    void replayRefusesWhenTheStoredInputsNoLongerAddUp() {
        FakeRunStore store = storeWith(someStatements(), someLedger());
        ReconciliationRunService service = new ReconciliationRunService(store, new TickingClock());
        service.start(request());
        store.inputs = new RunInputs(1, DATE, CONFIG, someStatements(), someLedger().subList(0, 1));

        RunRejectedException refused = assertThrows(RunRejectedException.class, () -> service.replay(1));

        assertEquals(RunErrorCode.REPLAY_INPUTS_UNAVAILABLE, refused.code());
    }

    @Test
    void onlyACompletedRunCanBeReplayed() {
        FakeRunStore store = storeWith(someStatements(), someLedger());
        store.failOnLoad = new IllegalStateException("x");
        ReconciliationRunService service = new ReconciliationRunService(store, new TickingClock());
        assertThrows(IllegalStateException.class, () -> service.start(request()));

        RunRejectedException refused = assertThrows(RunRejectedException.class, () -> service.replay(1));

        assertEquals(RunErrorCode.RUN_NOT_COMPLETED, refused.code());
    }

    @Test
    void replayingAnUnknownRunIsNotFound() {
        ReconciliationRunService service = new ReconciliationRunService(new FakeRunStore(), new TickingClock());

        RunRejectedException refused = assertThrows(RunRejectedException.class, () -> service.replay(7));

        assertEquals(RunErrorCode.RUN_NOT_FOUND, refused.code());
    }
}