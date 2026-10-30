package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.saumay.payments.application.CaseErrorCode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Every case error code has a deliberate HTTP status. The test lists them all, so adding a code without deciding its
 * status fails the build here instead of surfacing as a surprise 500 in production.
 */
class CaseErrorStatusTest {

    private static final Map<CaseErrorCode, HttpStatus> EXPECTED = Map.of(
            CaseErrorCode.CASE_NOT_FOUND, HttpStatus.NOT_FOUND,
            CaseErrorCode.CASE_VERSION_CONFLICT, HttpStatus.CONFLICT,
            CaseErrorCode.ILLEGAL_STATE_TRANSITION, HttpStatus.CONFLICT,
            CaseErrorCode.CASE_OWNER_REQUIRED, HttpStatus.CONFLICT,
            CaseErrorCode.CASE_REQUEST_INVALID, HttpStatus.BAD_REQUEST,
            CaseErrorCode.MATCH_INVALID, HttpStatus.UNPROCESSABLE_CONTENT,
            CaseErrorCode.MATCH_LINE_NOT_FOUND, HttpStatus.UNPROCESSABLE_CONTENT,
            CaseErrorCode.MATCH_LINE_ALREADY_CLAIMED, HttpStatus.CONFLICT,
            CaseErrorCode.NOTHING_TO_UNMATCH, HttpStatus.CONFLICT);

    @Test
    void everyCodeIsListedHere() {
        assertEquals(CaseErrorCode.values().length, EXPECTED.size(), "a new code needs a status decision");
    }

    @Test
    void everyCodeMapsToItsDecidedStatus() {
        for (CaseErrorCode code : CaseErrorCode.values()) {
            assertEquals(EXPECTED.get(code), GlobalExceptionHandler.statusFor(code), code.name());
        }
    }
}