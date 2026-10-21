package com.saumay.payments.matching;

import java.util.List;

/**
 * One candidate one-to-many decomposition: a single line on one side and the several lines on the other side
 * that sum exactly to it. Several proposals for the same lines are the ambiguity tier 4 must not hide.
 */
record Proposal(MatchableLine single, boolean singleIsStatement, List<MatchableLine> components,
                MatchScore score, String reason) {

    Proposal {
        components = List.copyOf(components);
    }

    List<MatchableLine> statementSide() {
        return singleIsStatement ? List.of(single) : components;
    }

    List<MatchableLine> ledgerSide() {
        return singleIsStatement ? components : List.of(single);
    }
}