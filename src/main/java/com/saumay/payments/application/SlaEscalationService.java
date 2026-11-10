package com.saumay.payments.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Performs one bounded-page scan and attempts each overdue transition. */
public final class SlaEscalationService {

    private static final int PAGE_SIZE = 250;

    private final SlaCandidateReader candidates;
    private final CaseService cases;

    public SlaEscalationService(SlaCandidateReader candidates, CaseService cases) {
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.cases = Objects.requireNonNull(cases, "cases");
    }

    /** Returns the number of transitions actually committed by this invocation. */
    public long run(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        long after = 0;
        long escalated = 0;
        while (true) {
            List<SlaCandidateReader.Candidate> page = candidates.overdueAfter(asOf, after, PAGE_SIZE);
            if (page.isEmpty()) {
                return escalated;
            }
            for (SlaCandidateReader.Candidate candidate : page) {
                if (candidate.id() <= after) {
                    throw new IllegalStateException("SLA candidates must be in increasing ID order");
                }
                if (cases.escalateForSla(candidate.id(), candidate.version(), asOf)) {
                    escalated = Math.addExact(escalated, 1);
                }
                after = candidate.id();
            }
            if (page.size() < PAGE_SIZE) {
                return escalated;
            }
        }
    }
}