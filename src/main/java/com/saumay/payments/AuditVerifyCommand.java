package com.saumay.payments;

import com.saumay.payments.application.AuditLog;
import com.saumay.payments.domain.audit.AuditVerification;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/** Runs audit verification as a short-lived, non-web application. */
final class AuditVerifyCommand {

    private AuditVerifyCommand() {
    }

    static boolean requested(String[] args) {
        return Arrays.asList(args).contains("--audit.verify");
    }

    static int run(String[] args) {
        SpringApplication application = new SpringApplication(PaymentsApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run(args)) {
            AuditLog audit = context.getBean(AuditLog.class);
            com.saumay.payments.domain.audit.AuditHead expected = expectedAnchor(args);
            AuditVerification result = expected == null ? audit.verify() : audit.verify(expected);
            System.out.println(describe(result));
            return exitCode(result);
        }
    }

    static com.saumay.payments.domain.audit.AuditHead expectedAnchor(String[] args) {
        String seq = null;
        String hash = null;
        for (String arg : args) {
            if (arg.startsWith("--audit.expected-seq=")) {
                if (seq != null) {
                    throw new IllegalArgumentException("audit.expected-seq was supplied more than once");
                }
                seq = arg.substring("--audit.expected-seq=".length());
            } else if (arg.startsWith("--audit.expected-hash=")) {
                if (hash != null) {
                    throw new IllegalArgumentException("audit.expected-hash was supplied more than once");
                }
                hash = arg.substring("--audit.expected-hash=".length());
            }
        }
        if (seq == null && hash == null) {
            return null;
        }
        if (seq == null || hash == null) {
            throw new IllegalArgumentException("both audit anchor options are required");
        }
        return new com.saumay.payments.domain.audit.AuditHead(Long.parseLong(seq), hash);
    }

    static int exitCode(AuditVerification result) {
        return result instanceof AuditVerification.Ok ? 0 : 3;
    }

    static String describe(AuditVerification result) {
        if (result instanceof AuditVerification.Ok ok) {
            return "audit.verify status=OK count=" + ok.count()
                    + " headSeq=" + ok.headSeq() + " headHash=" + ok.headHash();
        }
        if (result instanceof AuditVerification.AnchorMismatch mismatch) {
            return "audit.verify status=FAILED reason=" + mismatch.reason()
                    + " expectedSeq=" + mismatch.expected().seq()
                    + " expectedHash=" + mismatch.expected().hash()
                    + " headSeq=" + mismatch.actual().seq()
                    + " headHash=" + mismatch.actual().hash();
        }
        AuditVerification.Failed failed = (AuditVerification.Failed) result;
        return "audit.verify status=FAILED firstBadSeq=" + failed.firstBadSeq()
                + " reason=" + failed.reason() + " verifiedCount=" + failed.verifiedCount();
    }
}