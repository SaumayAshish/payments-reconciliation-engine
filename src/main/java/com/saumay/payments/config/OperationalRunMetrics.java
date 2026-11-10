package com.saumay.payments.config;

import com.saumay.payments.application.OperationalMetricsReader;
import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.matching.MatchTier;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Gauges for the latest completed run and current unresolved exception kinds. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class OperationalRunMetrics {

    private final OperationalMetricsReader reader;
    private volatile Snapshot snapshot = new Snapshot(Double.NaN, 0, 0, 0, 0, Map.of());

    public OperationalRunMetrics(OperationalMetricsReader reader, MeterRegistry meters) {
        this.reader = Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(meters, "meters");

        Gauge.builder("reconciliation_match_rate", this, metrics -> metrics.snapshot.matchRate())
                .register(meters);
        for (MatchTier tier : MatchTier.values()) {
            Gauge.builder("matching_tier_distribution", this, metrics -> metrics.tierCount(tier))
                    .tag("tier", tier.name())
                    .register(meters);
        }
        for (CaseKind kind : CaseKind.values()) {
            Gauge.builder("reconciliation_exception_count", this,
                            metrics -> metrics.snapshot.exceptions().getOrDefault(kind, 0L).doubleValue())
                    .tag("kind", kind.name())
                    .register(meters);
        }
    }

    @Scheduled(fixedDelayString = "${payments.metrics.refresh-ms:60000}")
    public void refresh() {
        var run = reader.latestCompletedRun();
        Map<CaseKind, Long> exceptions = reader.unresolvedByKind();
        if (run.isEmpty()) {
            snapshot = new Snapshot(Double.NaN, 0, 0, 0, 0, exceptions);
            return;
        }
        var value = run.get();
        double rate = value.statementLines() == 0 ? Double.NaN
                : (double) value.autoMatchedStatementLines() / value.statementLines();
        snapshot = new Snapshot(rate, value.exact(), value.tolerance(), value.fuzzy(),
                value.oneToMany(), exceptions);
    }

    private double tierCount(MatchTier tier) {
        Snapshot value = snapshot;
        return switch (tier) {
            case EXACT -> value.exact();
            case TOLERANCE -> value.tolerance();
            case FUZZY -> value.fuzzy();
            case ONE_TO_MANY -> value.oneToMany();
        };
    }

    private record Snapshot(double matchRate, long exact, long tolerance, long fuzzy,
            long oneToMany, Map<CaseKind, Long> exceptions) {
        private Snapshot {
            exceptions = Map.copyOf(exceptions);
        }
    }
}