package com.saumay.payments.domain.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuditTimeTest {

    @Test
    void anInstantBecomesWholeMicrosecondsAndNanosecondsAreDropped() {
        // 2026-11-03T14:20:00Z is 1793715600 seconds after the epoch (computed independently).
        assertEquals(1793715600123456L, AuditTime.micros(Instant.parse("2026-11-03T14:20:00.123456789Z")));
        assertEquals(1793715600000000L, AuditTime.micros(Instant.parse("2026-11-03T14:20:00Z")));
    }

    @Test
    void theEpochIsZeroAndTimesBeforeItAreNegative() {
        assertEquals(0L, AuditTime.micros(Instant.EPOCH));
        assertEquals(-1_000_000L + 500_000L, AuditTime.micros(Instant.parse("1969-12-31T23:59:59.5Z")));
    }

    @Test
    void microsRoundTripToTheSameInstantAtMicrosecondPrecision() {
        Instant original = Instant.parse("2026-11-03T14:20:00.123456789Z");
        assertEquals(Instant.parse("2026-11-03T14:20:00.123456Z"), AuditTime.instant(AuditTime.micros(original)));
        Instant before = Instant.parse("1969-12-31T23:59:59.250001Z");
        assertEquals(before, AuditTime.instant(AuditTime.micros(before)));
    }

    @Test
    void anInstantTooFarOutToHoldAsMicrosecondsIsRefusedNotWrapped() {
        assertThrows(ArithmeticException.class, () -> AuditTime.micros(Instant.MAX));
    }
}