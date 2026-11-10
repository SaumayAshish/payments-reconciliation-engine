package com.saumay.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.saumay.payments.application.ApprovalErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApprovalErrorStatusTest {

    @Test
    void everyApprovalRefusalHasItsOwnStatus() {
        assertEquals(HttpStatus.NOT_FOUND, GlobalExceptionHandler.statusFor(ApprovalErrorCode.APPROVAL_NOT_FOUND));
        assertEquals(HttpStatus.CONFLICT, GlobalExceptionHandler.statusFor(ApprovalErrorCode.APPROVAL_PENDING));
        assertEquals(HttpStatus.CONFLICT,
                GlobalExceptionHandler.statusFor(ApprovalErrorCode.APPROVAL_ALREADY_DECIDED));
        assertEquals(HttpStatus.FORBIDDEN,
                GlobalExceptionHandler.statusFor(ApprovalErrorCode.SEPARATION_OF_DUTIES_VIOLATION));
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT,
                GlobalExceptionHandler.statusFor(ApprovalErrorCode.CONTROLLED_ACTION_NOT_ALLOWED));
    }
}