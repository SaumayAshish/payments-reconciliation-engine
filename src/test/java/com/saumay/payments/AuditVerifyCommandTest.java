package com.saumay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.audit.AuditVerification;
import org.junit.jupiter.api.Test;

class AuditVerifyCommandTest {

    @Test
    void anIntactChainReportsItsHeadAndUsesExitZero() {
        var result = new AuditVerification.Ok(2, 2, "a".repeat(64));

        assertEquals(0, AuditVerifyCommand.exitCode(result));
        assertEquals("audit.verify status=OK count=2 headSeq=2 headHash=" + "a".repeat(64),
                AuditVerifyCommand.describe(result));
    }

    @Test
    void aBrokenChainReportsTheFirstBadSequenceAndUsesExitThree() {
        var result = new AuditVerification.Failed(
                5, AuditVerification.FailureReason.HASH_MISMATCH, 4);

        assertEquals(3, AuditVerifyCommand.exitCode(result));
        assertEquals("audit.verify status=FAILED firstBadSeq=5 reason=HASH_MISMATCH verifiedCount=4",
                AuditVerifyCommand.describe(result));
    }

    @Test
    void anchorOptionsMustBePairedAndWellFormed() {
        var anchor = AuditVerifyCommand.expectedAnchor(new String[] {
                "--audit.expected-seq=0", "--audit.expected-hash=" + "0".repeat(64)});
        assertEquals(0, anchor.seq());
        assertEquals("0".repeat(64), anchor.hash());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> AuditVerifyCommand.expectedAnchor(new String[] {"--audit.expected-seq=1"}));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> AuditVerifyCommand.expectedAnchor(new String[] {
                        "--audit.expected-seq=-1", "--audit.expected-hash=" + "0".repeat(64)}));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> AuditVerifyCommand.expectedAnchor(new String[] {
                        "--audit.expected-seq=1", "--audit.expected-hash=invalid"}));
    }

    @Test
    void anAnchorMismatchUsesExitThreeAndNamesBothHeads() {
        var expected = new com.saumay.payments.domain.audit.AuditHead(1, "a".repeat(64));
        var actual = new com.saumay.payments.domain.audit.AuditHead(2, "b".repeat(64));
        var result = new AuditVerification.AnchorMismatch(expected, actual);

        assertEquals(3, AuditVerifyCommand.exitCode(result));
        assertTrue(AuditVerifyCommand.describe(result).contains("reason=HEAD_MISMATCH"));
        assertTrue(AuditVerifyCommand.describe(result).contains("expectedSeq=1"));
        assertTrue(AuditVerifyCommand.describe(result).contains("headSeq=2"));
    }

    @Test
    void commandModeRequiresTheExactFlag() {
        assertTrue(AuditVerifyCommand.requested(new String[] {"--audit.verify"}));
        assertEquals(false, AuditVerifyCommand.requested(new String[] {"--audit.verify=false"}));
        assertEquals(false, AuditVerifyCommand.requested(new String[] {}));
    }
}