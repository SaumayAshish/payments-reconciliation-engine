package com.saumay.payments.domain.audit;

import java.time.Instant;

/**
 * Audit timestamps are whole microseconds since the epoch, stored and hashed as a number so no database or driver
 * can reformat them (ADR-0004). Anything finer than a microsecond is dropped, which is what PostgreSQL would do too.
 */
public final class AuditTime {

    private static final long MICROS_PER_SECOND = 1_000_000L;

    private AuditTime() {
    }

    public static long micros(Instant at) {
        return Math.addExact(Math.multiplyExact(at.getEpochSecond(), MICROS_PER_SECOND), at.getNano() / 1_000);
    }

    public static Instant instant(long micros) {
        return Instant.ofEpochSecond(Math.floorDiv(micros, MICROS_PER_SECOND),
                Math.floorMod(micros, MICROS_PER_SECOND) * 1_000L);
    }
}