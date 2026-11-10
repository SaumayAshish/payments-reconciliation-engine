package com.saumay.payments.domain.reporting;

import com.saumay.payments.domain.cases.SlaPolicy;
import com.saumay.payments.domain.cases.SlaStatus;
import java.time.LocalDate;
import java.util.Objects;

/** Age and SLA status calculated for one case against an explicit date. */
public record CaseAgeing(int ageBusinessDays, AgeingBucket bucket, SlaStatus slaStatus) {

    public CaseAgeing {
        if (ageBusinessDays < 0) {
            throw new IllegalArgumentException("ageBusinessDays must not be negative");
        }
        Objects.requireNonNull(bucket, "bucket");
        Objects.requireNonNull(slaStatus, "slaStatus");
        if (bucket != AgeingBucket.forAge(ageBusinessDays)) {
            throw new IllegalArgumentException("bucket does not match ageBusinessDays");
        }
    }

    public static CaseAgeing evaluate(LocalDate createdBusinessDate, int slaBusinessDays,
            LocalDate asOf, SlaPolicy policy) {
        Objects.requireNonNull(createdBusinessDate, "createdBusinessDate");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(policy, "policy");
        int age = SlaPolicy.ageInBusinessDays(createdBusinessDate, asOf);
        SlaStatus status = policy.status(createdBusinessDate, slaBusinessDays, asOf);
        return new CaseAgeing(age, AgeingBucket.forAge(age), status);
    }
}