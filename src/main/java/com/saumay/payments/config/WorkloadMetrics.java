package com.saumay.payments.config;

import com.saumay.payments.application.WorkloadReportReader;
import com.saumay.payments.domain.cases.CaseStatus;
import com.saumay.payments.domain.cases.CaseTeam;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Maintains low-cardinality queue-depth gauges from the last successful refresh. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class WorkloadMetrics {

    private final WorkloadReportReader reader;
    private volatile Map<Key, Long> counts = Map.of();

    public WorkloadMetrics(WorkloadReportReader reader, MeterRegistry meters) {
        this.reader = Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(meters, "meters");
        for (CaseTeam team : CaseTeam.values()) {
            for (CaseStatus status : CaseStatus.values()) {
                if (status == CaseStatus.RESOLVED) {
                    continue;
                }
                Key key = new Key(team, status);
                Gauge.builder("exception_queue_depth", this,
                                metrics -> metrics.counts.getOrDefault(key, 0L).doubleValue())
                        .tag("team", team.name())
                        .tag("status", status.name())
                        .register(meters);
            }
        }
    }

    @Scheduled(fixedDelayString = "${payments.metrics.refresh-ms:60000}")
    public void refresh() {
        Map<Key, Long> next = new HashMap<>();
        for (WorkloadReportReader.Group group : reader.currentWorkload()) {
            next.put(new Key(group.team(), group.status()), group.count());
        }
        counts = Map.copyOf(next);
    }

    private record Key(CaseTeam team, CaseStatus status) {
    }
}