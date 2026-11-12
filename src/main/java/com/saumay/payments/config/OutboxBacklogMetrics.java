package com.saumay.payments.config;

import com.saumay.payments.application.OutboxDeliveryStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reports the last successfully refreshed count of unpublished outbox rows. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class OutboxBacklogMetrics {

    private final OutboxDeliveryStore store;
    private volatile double backlog = Double.NaN;

    public OutboxBacklogMetrics(OutboxDeliveryStore store, MeterRegistry meters) {
        this.store = Objects.requireNonNull(store, "store");
        Gauge.builder("outbox_backlog", this, metric -> metric.backlog)
                .register(Objects.requireNonNull(meters, "meters"));
    }

    @Scheduled(fixedDelayString = "${payments.metrics.refresh-ms:60000}")
    public void refresh() {
        backlog = store.backlog();
    }
}