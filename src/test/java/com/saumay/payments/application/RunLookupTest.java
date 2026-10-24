package com.saumay.payments.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saumay.payments.matching.MatchingConfig;
import com.saumay.payments.matching.RunSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunLookupTest {

    private static RunHeader run(RunStatus status) {
        return new RunHeader(1, LocalDate.of(2026, 10, 9), status, MatchingConfig.defaults(), 5, List.of(1L),
                Instant.EPOCH, null, null, null, null, status == RunStatus.COMPLETED
                        ? new RunSummary(0, 0, 0, 0, 0, 0, 0, 0, 0) : null, null);
    }

    private static ReconciliationRunService serviceWith(RunHeader header) {
        FakeRunStore store = new FakeRunStore();
        store.header = header;
        return new ReconciliationRunService(store, Clock.systemUTC());
    }

    @Test
    void anUnknownRunIsNotFound() {
        RunRejectedException refused =
                assertThrows(RunRejectedException.class, () -> serviceWith(null).get(7));

        assertEquals(RunErrorCode.RUN_NOT_FOUND, refused.code());
    }

    @Test
    void aStoredRunIsReturnedInWhateverStateItIsIn() {
        RunHeader created = run(RunStatus.CREATED);

        assertSame(created, serviceWith(created).get(1));
    }

    @Test
    void aRunThatHasNotCompletedHasNoResults() {
        for (RunStatus status : List.of(RunStatus.CREATED, RunStatus.RUNNING, RunStatus.FAILED)) {
            RunRejectedException refused = assertThrows(RunRejectedException.class,
                    () -> serviceWith(run(status)).requireCompleted(1), status.name());

            assertEquals(RunErrorCode.RUN_NOT_COMPLETED, refused.code(), status.name());
        }
    }

    @Test
    void aCompletedRunPassesTheCompletionCheck() {
        RunHeader completed = run(RunStatus.COMPLETED);

        assertSame(completed, serviceWith(completed).requireCompleted(1));
    }
}