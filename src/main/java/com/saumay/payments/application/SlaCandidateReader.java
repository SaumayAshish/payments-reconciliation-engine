package com.saumay.payments.application;

import java.time.LocalDate;
import java.util.List;

/** Reads bounded pages of cases that may need an SLA escalation. */
public interface SlaCandidateReader {

    List<Candidate> overdueAfter(LocalDate asOf, long afterId, int limit);

    record Candidate(long id, long version) {
        public Candidate {
            if (id < 1 || version < 0) {
                throw new IllegalArgumentException("invalid case id or version");
            }
        }
    }
}