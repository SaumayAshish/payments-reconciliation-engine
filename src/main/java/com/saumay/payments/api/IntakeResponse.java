package com.saumay.payments.api;

import com.saumay.payments.application.IntakeResult;

/** What opening the cases of one run did. {@code created + alreadyOpen == exceptions}. */
public record IntakeResponse(long runId, int exceptions, int created, int alreadyOpen) {

    static IntakeResponse from(IntakeResult result) {
        return new IntakeResponse(result.runId(), result.exceptions(), result.created(), result.alreadyOpen());
    }
}