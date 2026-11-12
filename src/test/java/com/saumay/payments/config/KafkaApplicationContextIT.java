package com.saumay.payments.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.PaymentsApplication;
import com.saumay.payments.application.EventTransport;
import com.saumay.payments.application.OutboxPublisher;
import com.saumay.payments.infrastructure.messaging.RunCompletedListener;
import com.saumay.payments.infrastructure.persistence.JdbcRunEventProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies the opt-in event components are wired in the real application. */
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(
        classes = PaymentsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "payments.events.enabled=true",
            "payments.events.poll-ms=1000",
            "spring.kafka.listener.missing-topics-fatal=false"
        })
class KafkaApplicationContextIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka-native:3.8.0");

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Test
    void enabledApplicationWiresPublisherAndStartsRunConsumer() {
        assertNotNull(context.getBean(EventTransport.class));
        assertNotNull(context.getBean(OutboxPublisher.class));
        assertNotNull(context.getBean(JdbcRunEventProcessor.class));
        assertNotNull(context.getBean(RunCompletedListener.class));

        KafkaListenerEndpointRegistry registry =
                context.getBean(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer listener =
                registry.getListenerContainer("payments-run-completed-intake");

        assertNotNull(listener);
        assertTrue(listener.isRunning(), "RUN_COMPLETED listener must be running");
    }
}