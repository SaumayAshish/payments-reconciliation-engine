package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.application.OutboxPublisher;
import com.saumay.payments.infrastructure.messaging.KafkaEventTransport;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class KafkaOutboxPublisherIT {

    private static final String TOPIC = "payments.run.completed.v1";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        try (AdminClient admin = AdminClient.create(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void clearOutbox() {
        jdbc.execute("TRUNCATE outbox_event RESTART IDENTITY");
    }

    @Test
    void acknowledgedEventIsPublishedAndCanBeConsumed() throws Exception {
        createEvent();
        var store = new JdbcOutboxDeliveryStore(dataSource);
        var factory = producerFactory(KAFKA.getBootstrapServers(), 5000, 10000, 15000);

        try {
            var publisher = new OutboxPublisher(store,
                    new KafkaEventTransport(new KafkaTemplate<>(factory)), Clock.systemUTC());
            assertEquals(OutboxPublisher.Outcome.PUBLISHED, publisher.publishOne());
            assertEquals(0, store.backlog());

            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
                consumer.subscribe(List.of(TOPIC));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                boolean found = false;
                while (System.nanoTime() < deadline && !found) {
                    var records = consumer.poll(Duration.ofMillis(500));
                    for (var record : records) {
                        if ("42".equals(record.key()) && record.value().contains("\"runId\":42")) {
                            found = true;
                        }
                    }
                }
                assertTrue(found, "The acknowledged event was not consumed");
            }
        } finally {
            factory.destroy();
        }
    }

    @Test
    void unavailableBrokerLeavesTheEventPendingForRetry() {
        createEvent();
        var store = new JdbcOutboxDeliveryStore(dataSource);
        var factory = producerFactory("127.0.0.1:1", 1000, 1000, 2000);

        try {
            var publisher = new OutboxPublisher(store,
                    new KafkaEventTransport(new KafkaTemplate<>(factory)), Clock.systemUTC());
            assertEquals(OutboxPublisher.Outcome.RETRY_SCHEDULED, publisher.publishOne());
            assertEquals(1, store.backlog());
            assertEquals("PENDING", jdbc.queryForObject(
                    "SELECT status FROM outbox_event", String.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT attempt_count FROM outbox_event", Integer.class));
        } finally {
            factory.destroy();
        }
    }

    private static void createEvent() {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status ->
                JdbcOutbox.runCompleted(jdbc, 42, Clock.systemUTC().instant()));
        jdbc.update("UPDATE outbox_event SET next_attempt_at = now() - interval '1 second'");
    }

    private static DefaultKafkaProducerFactory<String, String> producerFactory(
            String bootstrap, int maxBlockMs, int requestTimeoutMs, int deliveryTimeoutMs) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        return new DefaultKafkaProducerFactory<>(props);
    }
}