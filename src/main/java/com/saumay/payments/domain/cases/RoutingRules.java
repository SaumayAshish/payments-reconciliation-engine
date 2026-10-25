package com.saumay.payments.domain.cases;

import com.saumay.payments.domain.model.BusinessCalendar;
import com.saumay.payments.domain.money.CurrencyCode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Case routing (FRD-06, ADR-0008): an ordered list of rules where the first match wins and the name of the rule that
 * matched is returned with the answer, so every assignment can be explained later.
 *
 * <ol>
 *   <li>a currency mismatch goes to the FX team at P2, whatever its size;</li>
 *   <li>an amount above the high-value threshold of its currency goes to a senior analyst at P1;</li>
 *   <li>a missing ledger entry goes to ledger operations at P3;</li>
 *   <li>a missing statement line goes to bank operations at P3;</li>
 *   <li>everything else goes to the reconciliation analysts at P3, a suspected duplicate at P4.</li>
 * </ol>
 *
 * <p>Rule 6, an SLA breach, is applied by {@link #escalated}. Pure: no clock, no I/O.
 */
public final class RoutingRules {

    public static final String RULE_CURRENCY_MISMATCH = "currency-mismatch";
    public static final String RULE_HIGH_VALUE = "high-value";
    public static final String RULE_MISSING_LEDGER = "missing-ledger";
    public static final String RULE_MISSING_STATEMENT = "missing-statement";
    public static final String RULE_ANALYST_REVIEW = "analyst-review";
    public static final String RULE_SLA_BREACH = "sla-breach-escalation";

    /** Where a case goes, at what priority, and which rule said so. */
    public record Routing(CaseTeam team, CasePriority priority, String ruleName) {
        public Routing {
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(priority, "priority");
            Objects.requireNonNull(ruleName, "ruleName");
        }
    }

    /** Everything decided when a case is created (AC-06.1). */
    public record CasePlan(CaseClass caseClass, Routing routing, int slaBusinessDays, LocalDate dueBusinessDate) {
        public CasePlan {
            Objects.requireNonNull(caseClass, "caseClass");
            Objects.requireNonNull(routing, "routing");
            Objects.requireNonNull(dueBusinessDate, "dueBusinessDate");
        }
    }

    private final RoutingConfig config;

    public RoutingRules(RoutingConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public Routing route(CaseKind kind, long amountMinor, CurrencyCode currency) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(currency, "currency");
        if (kind != CaseKind.CURRENCY_MISMATCH && isHighValue(amountMinor, currency)) {
            return new Routing(CaseTeam.SENIOR_ANALYST, CasePriority.P1, RULE_HIGH_VALUE);
        }
        return switch (kind) {
            case CURRENCY_MISMATCH -> new Routing(CaseTeam.FX_TEAM, CasePriority.P2, RULE_CURRENCY_MISMATCH);
            case MISSING_LEDGER -> new Routing(CaseTeam.LEDGER_OPERATIONS, CasePriority.P3, RULE_MISSING_LEDGER);
            case MISSING_STATEMENT ->
                    new Routing(CaseTeam.BANK_OPERATIONS, CasePriority.P3, RULE_MISSING_STATEMENT);
            case DUPLICATE_SUSPECT -> new Routing(CaseTeam.RECON_ANALYSTS, CasePriority.P4, RULE_ANALYST_REVIEW);
            case AMOUNT_MISMATCH, LOW_CONFIDENCE, AMBIGUOUS_TIE ->
                    new Routing(CaseTeam.RECON_ANALYSTS, CasePriority.P3, RULE_ANALYST_REVIEW);
        };
    }

    /** Routing plus class, SLA length and due date, all fixed at creation. */
    public CasePlan plan(CaseKind kind, long amountMinor, CurrencyCode currency, LocalDate createdBusinessDate) {
        Routing routing = route(kind, amountMinor, currency);
        int slaDays = config.sla().businessDaysFor(routing.priority());
        LocalDate due = BusinessCalendar.addBusinessDays(createdBusinessDate, slaDays);
        return new CasePlan(kind.caseClass(), routing, slaDays, due);
    }

    /**
     * Rule 6: an escalated case goes to the team lead one priority level higher. The SLA length and due date
     * stay as they were, so the breach remains visible (ADR-0008).
     */
    public Routing escalated(Routing current) {
        Objects.requireNonNull(current, "current");
        return new Routing(CaseTeam.TEAM_LEAD, current.priority().raisedOneLevel(), RULE_SLA_BREACH);
    }

    /** Strictly above the threshold, in either direction; written without {@code abs} so no amount can overflow. */
    private boolean isHighValue(long amountMinor, CurrencyCode currency) {
        long threshold = config.thresholdFor(currency);
        return amountMinor > threshold || amountMinor < -threshold;
    }
}