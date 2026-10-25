package com.saumay.payments.application;

/** What opening cases for one run did: how many exceptions it holds, how many became new cases. */
public record IntakeResult(long runId, int exceptions, int created, int alreadyOpen) {
}