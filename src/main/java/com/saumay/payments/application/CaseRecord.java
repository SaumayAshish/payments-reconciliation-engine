package com.saumay.payments.application;

import com.saumay.payments.domain.cases.CaseClass;
import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CasePriority;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.CaseTeam;
import com.saumay.payments.domain.cases.RoutingRules.Routing;
import com.saumay.payments.domain.money.CurrencyCode;
import java.time.LocalDate;
import java.util.Objects;

/** A case as stored. The version is what optimistic locking compares. */
public record CaseRecord(long id, CaseKind kind, CaseClass caseClass, long amountMinor, CurrencyCode currency,
        CaseStatus status, CasePriority priority, CaseTeam team, String routingRule, String owner,
        LocalDate createdBusinessDate, int slaBusinessDays, LocalDate dueBusinessDate, long version) {

    public CaseRecord {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(caseClass, "caseClass");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(routingRule, "routingRule");
        Objects.requireNonNull(createdBusinessDate, "createdBusinessDate");
        Objects.requireNonNull(dueBusinessDate, "dueBusinessDate");
    }

    public Routing routing() {
        return new Routing(team, priority, routingRule);
    }
}