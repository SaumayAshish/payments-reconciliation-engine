package com.saumay.payments.application;

import com.saumay.payments.application.CaseIntakeStore.IntakeItem;
import com.saumay.payments.application.CaseIntakeStore.IntakeRun;
import com.saumay.payments.application.CaseIntakeStore.NewCase;
import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.RoutingConfig;
import com.saumay.payments.domain.cases.RoutingRules;
import com.saumay.payments.domain.cases.RoutingRules.CasePlan;
import com.saumay.payments.domain.money.CurrencyCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens a work item for every exception of a completed run (FRD-06, ADR-0007). A separate step after the run, so
 * the run's evidence stays untouched. Safe to repeat: a case exists once per natural key, so a second call, or a
 * later run raising the same break, creates nothing new and the original case keeps its age (D1).
 */
public final class CaseIntakeService {

    private static final Logger LOG = LoggerFactory.getLogger(CaseIntakeService.class);
    private static final String COMPLETED = "COMPLETED";

    private final CaseIntakeStore store;
    private final RoutingConfig config;
    private final RoutingRules rules;

    public CaseIntakeService(CaseIntakeStore store, RoutingConfig config) {
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
        this.rules = new RoutingRules(config);
    }

    /**
     * @throws RunRejectedException RUN_NOT_FOUND, or RUN_NOT_COMPLETED when the run has no results to work from
     */
    public IntakeResult openCases(long runId) {
        IntakeRun run = store.loadIntake(runId).orElseThrow(
                () -> new RunRejectedException(RunErrorCode.RUN_NOT_FOUND, "No run exists with id " + runId));
        if (!COMPLETED.equals(run.status())) {
            throw new RunRejectedException(RunErrorCode.RUN_NOT_COMPLETED,
                    "Run " + runId + " is " + run.status() + "; cases can only be opened from a completed run");
        }
        List<NewCase> cases = new ArrayList<>(run.items().size());
        Set<CurrencyCode> warned = new TreeSet<>();
        for (IntakeItem item : run.items()) {
            cases.add(toCase(run, item, warned));
        }
        int created = cases.isEmpty() ? 0 : store.insert(cases);
        return new IntakeResult(runId, cases.size(), created, cases.size() - created);
    }

    private NewCase toCase(IntakeRun run, IntakeItem item, Set<CurrencyCode> warned) {
        CaseKind kind = CaseKind.valueOf(item.kind());
        CurrencyCode currency = CurrencyCode.of(item.currency());
        if (!config.hasThresholdFor(currency) && warned.add(currency)) {
            LOG.warn("No high-value threshold configured for {}; using the default of {} minor units",
                    currency, config.defaultHighValueThresholdMinor());
        }
        CasePlan plan = rules.plan(kind, item.amountMinor(), currency, run.businessDate());
        return new NewCase(run.runId(), item.ordinal(), item.naturalKey(), kind, plan.caseClass(),
                item.amountMinor(), currency, plan.routing(), plan.slaBusinessDays(), run.businessDate(),
                plan.dueBusinessDate());
    }
}