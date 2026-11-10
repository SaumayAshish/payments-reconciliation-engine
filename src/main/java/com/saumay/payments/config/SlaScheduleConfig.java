package com.saumay.payments.config;

import com.saumay.payments.application.SlaEscalationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Schedules SLA escalation for the configured business time zone. */
@Configuration
@EnableScheduling
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SlaScheduleConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlaScheduleConfig.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private final SlaEscalationService escalations;
    private final Clock clock;
    private final Counter breaches;

    public SlaScheduleConfig(SlaEscalationService escalations, Clock clock, MeterRegistry meters) {
        this.escalations = Objects.requireNonNull(escalations, "escalations");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.breaches = Objects.requireNonNull(meters, "meters").counter("sla_breach_count");
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Kolkata")
    public void escalate() {
        LocalDate asOf = clock.instant().atZone(BUSINESS_ZONE).toLocalDate();
        long count = escalations.run(asOf);
        breaches.increment(count);
        LOGGER.info("sla.escalation asOf={} escalated={}", asOf, count);
    }
}