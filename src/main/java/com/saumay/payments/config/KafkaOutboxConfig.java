package com.saumay.payments.config;

import com.saumay.payments.application.EventTransport;
import com.saumay.payments.application.OutboxDeliveryStore;
import com.saumay.payments.application.OutboxPublisher;
import com.saumay.payments.infrastructure.messaging.KafkaEventTransport;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Wires opt-in Kafka outbox publication. */
@Configuration
@ConditionalOnProperty(prefix = "payments.events", name = "enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class KafkaOutboxConfig {

    @Bean(name = "taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("payments-scheduled-");
        return scheduler;
    }

    @Bean(name = "outboxScheduler")
    ThreadPoolTaskScheduler outboxScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("payments-outbox-");
        return scheduler;
    }

    @Bean
    EventTransport eventTransport(KafkaTemplate<String, String> kafka) {
        return new KafkaEventTransport(kafka);
    }

    @Bean
    OutboxPublisher outboxPublisher(OutboxDeliveryStore store, EventTransport transport, Clock clock) {
        return new OutboxPublisher(store, transport, clock);
    }

    @Bean
    OutboxPoller outboxPoller(OutboxPublisher publisher) {
        return new OutboxPoller(publisher);
    }

    static final class OutboxPoller {
        private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPoller.class);
        private final OutboxPublisher publisher;

        OutboxPoller(OutboxPublisher publisher) {
            this.publisher = publisher;
        }

        @Scheduled(fixedDelayString = "${payments.events.poll-ms:1000}", scheduler = "outboxScheduler")
        public void poll() {
            OutboxPublisher.Outcome outcome = publisher.publishOne();
            if (outcome != OutboxPublisher.Outcome.EMPTY) {
                LOGGER.info("outbox.publish outcome={}", outcome);
            }
        }
    }
}