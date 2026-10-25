package com.saumay.payments.application;

import com.saumay.payments.domain.cases.CaseClass;
import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.RoutingRules.Routing;
import com.saumay.payments.domain.money.CurrencyCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistence port for opening cases from a stored run (ADR-0007). The run's own result rows are never changed;
 * cases are separate, mutable records that point back at them.
 */
public interface CaseIntakeStore {

    /** The run's status, business date and one item per stored exception, in ordinal order. */
    Optional<IntakeRun> loadIntake(long runId);

    /**
     * Stores the cases that do not exist yet and returns how many were created. A case whose natural key already
     * exists is skipped, not updated, so calling this twice for one run changes nothing (AC-06.3). Every created
     * case gets its CREATED activity row in the same transaction.
     */
    int insert(List<NewCase> cases);

    /** A stored run as intake sees it. */
    record IntakeRun(long runId, String status, LocalDate businessDate, List<IntakeItem> items) {
        public IntakeRun {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(businessDate, "businessDate");
            items = List.copyOf(items);
        }
    }

    /**
     * One stored exception. Amount and currency come from its first subject line (the statement line when there
     * is one), signed as stored.
     */
    record IntakeItem(int ordinal, String kind, String naturalKey, long amountMinor, String currency) {
        public IntakeItem {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(naturalKey, "naturalKey");
            Objects.requireNonNull(currency, "currency");
        }
    }

    /** A case ready to be stored: everything decided at creation (AC-06.1). */
    record NewCase(long runId, int ordinal, String naturalKey, CaseKind kind, CaseClass caseClass,
            long amountMinor, CurrencyCode currency, Routing routing, int slaBusinessDays,
            LocalDate createdBusinessDate, LocalDate dueBusinessDate) {
        public NewCase {
            Objects.requireNonNull(naturalKey, "naturalKey");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(caseClass, "caseClass");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(routing, "routing");
            Objects.requireNonNull(createdBusinessDate, "createdBusinessDate");
            Objects.requireNonNull(dueBusinessDate, "dueBusinessDate");
        }
    }
}