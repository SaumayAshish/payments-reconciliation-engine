package com.saumay.payments.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saumay.payments.matching.MatchingConfig;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunRequestTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 9);

    @Test
    void theIngestionIdsAreASetKeptInOrderSoTheSameRequestAlwaysMeansTheSameRun() {
        RunRequest request = new RunRequest(DATE, List.of(7L, 3L, 7L, 5L), MatchingConfig.defaults());

        assertEquals(List.of(3L, 5L, 7L), request.statementIngestionIds());
        assertEquals(request, new RunRequest(DATE, List.of(5L, 7L, 3L), MatchingConfig.defaults()));
    }

    @Test
    void aRunNeedsAtLeastOneStatementIngestion() {
        assertThrows(IllegalArgumentException.class,
                () -> new RunRequest(DATE, List.of(), MatchingConfig.defaults()));
    }

    @Test
    void ingestionIdsMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new RunRequest(DATE, List.of(0L), MatchingConfig.defaults()));
        assertThrows(IllegalArgumentException.class,
                () -> new RunRequest(DATE, List.of(-4L, 2L), MatchingConfig.defaults()));
    }

    @Test
    void theBusinessDateAndConfigurationAreRequired() {
        assertThrows(NullPointerException.class, () -> new RunRequest(null, List.of(1L), MatchingConfig.defaults()));
        assertThrows(NullPointerException.class, () -> new RunRequest(DATE, List.of(1L), null));
    }
}