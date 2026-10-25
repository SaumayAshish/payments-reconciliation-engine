package com.saumay.payments.domain.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.saumay.payments.domain.cases.RoutingRules.CasePlan;
import com.saumay.payments.domain.cases.RoutingRules.Routing;
import com.saumay.payments.domain.money.CurrencyCode;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutingRulesTest {

    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final CurrencyCode JPY = CurrencyCode.of("JPY");
    private static final long LIMIT = RoutingConfig.STANDARD_DEFAULT_HIGH_VALUE_MINOR;
    // 2026-10-09 is a Friday.
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);

    private final RoutingRules rules = new RoutingRules(RoutingConfig.defaults());

    @Test
    void aCurrencyMismatchGoesToTheFxTeamAtP2() {
        assertEquals(new Routing(CaseTeam.FX_TEAM, CasePriority.P2, RoutingRules.RULE_CURRENCY_MISMATCH),
                rules.route(CaseKind.CURRENCY_MISMATCH, 5_000, GBP));
    }

    @Test
    void aHighValueCurrencyMismatchStillGoesToTheFxTeam() {
        // Rule 1 comes before rule 2 and the first match wins.
        assertEquals(new Routing(CaseTeam.FX_TEAM, CasePriority.P2, RoutingRules.RULE_CURRENCY_MISMATCH),
                rules.route(CaseKind.CURRENCY_MISMATCH, LIMIT * 10, GBP));
    }

    @Test
    void everyOtherKindAboveTheThresholdGoesToASeniorAnalystAtP1() {
        Routing expected = new Routing(CaseTeam.SENIOR_ANALYST, CasePriority.P1, RoutingRules.RULE_HIGH_VALUE);
        for (CaseKind kind : CaseKind.values()) {
            if (kind == CaseKind.CURRENCY_MISMATCH) {
                continue;
            }
            assertEquals(expected, rules.route(kind, LIMIT + 1, GBP), kind.toString());
            assertEquals(expected, rules.route(kind, -(LIMIT + 1), GBP), kind + " negative");
        }
    }

    @Test
    void anAmountEqualToTheThresholdIsNotHighValue() {
        assertEquals(RoutingRules.RULE_MISSING_LEDGER, rules.route(CaseKind.MISSING_LEDGER, LIMIT, GBP).ruleName());
        assertEquals(RoutingRules.RULE_MISSING_LEDGER, rules.route(CaseKind.MISSING_LEDGER, -LIMIT, GBP).ruleName());
        assertEquals(RoutingRules.RULE_HIGH_VALUE, rules.route(CaseKind.MISSING_LEDGER, LIMIT + 1, GBP).ruleName());
    }

    @Test
    void theLargestPossibleNegativeAmountDoesNotOverflow() {
        assertEquals(RoutingRules.RULE_HIGH_VALUE,
                rules.route(CaseKind.MISSING_LEDGER, Long.MIN_VALUE, GBP).ruleName());
        assertEquals(RoutingRules.RULE_HIGH_VALUE,
                rules.route(CaseKind.MISSING_LEDGER, Long.MAX_VALUE, GBP).ruleName());
    }

    @Test
    void aMissingLedgerEntryGoesToLedgerOperationsAtP3() {
        assertEquals(new Routing(CaseTeam.LEDGER_OPERATIONS, CasePriority.P3, RoutingRules.RULE_MISSING_LEDGER),
                rules.route(CaseKind.MISSING_LEDGER, 12_500, GBP));
    }

    @Test
    void aMissingStatementLineGoesToBankOperationsAtP3() {
        assertEquals(new Routing(CaseTeam.BANK_OPERATIONS, CasePriority.P3, RoutingRules.RULE_MISSING_STATEMENT),
                rules.route(CaseKind.MISSING_STATEMENT, 12_500, GBP));
    }

    @Test
    void mismatchesReviewsAndTiesGoToTheAnalystsAtP3() {
        Routing expected = new Routing(CaseTeam.RECON_ANALYSTS, CasePriority.P3, RoutingRules.RULE_ANALYST_REVIEW);
        assertEquals(expected, rules.route(CaseKind.AMOUNT_MISMATCH, 12_500, GBP));
        assertEquals(expected, rules.route(CaseKind.LOW_CONFIDENCE, 12_500, GBP));
        assertEquals(expected, rules.route(CaseKind.AMBIGUOUS_TIE, 12_500, GBP));
    }

    @Test
    void aSuspectedDuplicateBelowHighValueIsP4() {
        assertEquals(new Routing(CaseTeam.RECON_ANALYSTS, CasePriority.P4, RoutingRules.RULE_ANALYST_REVIEW),
                rules.route(CaseKind.DUPLICATE_SUSPECT, 12_500, GBP));
    }

    @Test
    void eachCurrencyUsesItsOwnThreshold() {
        RoutingRules perCurrency = new RoutingRules(
                new RoutingConfig(Map.of(JPY, 100_000L), LIMIT, SlaPolicy.standard()));
        assertEquals(RoutingRules.RULE_HIGH_VALUE, perCurrency.route(CaseKind.MISSING_LEDGER, 150_000, JPY).ruleName());
        assertEquals(RoutingRules.RULE_MISSING_LEDGER,
                perCurrency.route(CaseKind.MISSING_LEDGER, 150_000, GBP).ruleName());
    }

    @Test
    void everyKindIsRouted() {
        for (CaseKind kind : CaseKind.values()) {
            assertNotNull(rules.route(kind, 1_000, GBP), kind.toString());
        }
    }

    @Test
    void anEscalationGoesToTheTeamLeadOneLevelHigher() {
        Routing p3 = rules.route(CaseKind.MISSING_LEDGER, 12_500, GBP);
        assertEquals(new Routing(CaseTeam.TEAM_LEAD, CasePriority.P2, RoutingRules.RULE_SLA_BREACH),
                rules.escalated(p3));
        Routing p1 = rules.route(CaseKind.MISSING_LEDGER, LIMIT + 1, GBP);
        assertEquals(CasePriority.P1, rules.escalated(p1).priority());
    }

    @Test
    void aPlanFixesClassRoutingSlaAndDueDateAtCreation() {
        CasePlan plan = rules.plan(CaseKind.MISSING_LEDGER, 12_500, GBP, FRIDAY);
        assertEquals(CaseClass.BREAK, plan.caseClass());
        assertEquals(CaseTeam.LEDGER_OPERATIONS, plan.routing().team());
        assertEquals(5, plan.slaBusinessDays());
        assertEquals(LocalDate.of(2026, 10, 16), plan.dueBusinessDate());
    }

    @Test
    void aReviewKindIsPlannedAsAReviewCase() {
        assertEquals(CaseClass.REVIEW, rules.plan(CaseKind.LOW_CONFIDENCE, 12_500, GBP, FRIDAY).caseClass());
        assertEquals(CaseClass.REVIEW, rules.plan(CaseKind.AMBIGUOUS_TIE, 12_500, GBP, FRIDAY).caseClass());
    }

    @Test
    void aHighValuePlanIsP1WithAOneDayClock() {
        CasePlan plan = rules.plan(CaseKind.MISSING_LEDGER, LIMIT + 1, GBP, FRIDAY);
        assertEquals(CasePriority.P1, plan.routing().priority());
        assertEquals(1, plan.slaBusinessDays());
        assertEquals(LocalDate.of(2026, 10, 12), plan.dueBusinessDate());
    }
}