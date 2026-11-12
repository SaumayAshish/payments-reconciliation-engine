package com.saumay.payments.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.infrastructure.messaging.RunCompletedListener;
import com.saumay.payments.infrastructure.persistence.JdbcRunEventProcessor;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.listener.MessageListener;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Exercises the configured listener container against Kafka and PostgreSQL.
 * Case-intake transaction behavior is also covered by JdbcCaseIntakeStoreIT.
 */
@Testcontainers
class KafkaRunConsumerIT {

    private static final String TOPIC = "payments.run.completed.v1";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka-native:3.8.0");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() throws Exception {
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .load()
                .migrate();

        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());

        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE consumer_test_effect (run_id BIGINT PRIMARY KEY)");

        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void offsetsFollowCommittedWorkAndFailuresStopWithoutSkipping() throws Exception {
        String group = "run-consumer-it-" + UUID.randomUUID();
        UUID firstEvent = UUID.randomUUID();
        UUID secondEvent = UUID.randomUUID();
        AtomicBoolean failSecondOnce = new AtomicBoolean(true);

        JdbcRunEventProcessor processor = new JdbcRunEventProcessor(dataSource, runId -> {
            jdbc.update("INSERT INTO consumer_test_effect (run_id) VALUES (?)", runId);
            if (runId == 2L && failSecondOnce.getAndSet(false)) {
                throw new IllegalStateException("simulated intake failure");
            }
        });

        RunCompletedListener listener = new RunCompletedListener(processor);

        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(List.of(KAFKA.getBootstrapServers()));

        var factory = new KafkaRunConsumerConfig()
                .runEventKafkaListenerContainerFactory(kafkaProperties);

        var container = factory.createContainer(TOPIC);
        container.getContainerProperties().setGroupId(group);
        container.getContainerProperties().setMessageListener(
                (MessageListener<String, String>) listener::onMessage);

        try (AdminClient admin = admin(); KafkaProducer<String, String> producer = producer()) {
            send(producer, "1", payload(firstEvent, 1L));
            send(producer, "1", payload(firstEvent, 1L));

            container.start();

            await(
                    () -> committedOffset(admin, group) == 2L,
                    "Both copies of the first event should be acknowledged");

            assertEquals(1, count("processed_run_event"));
            assertEquals(1, count("consumer_test_effect"));

            send(producer, "2", payload(secondEvent, 2L));

            await(
                    () -> failSecondOnce.get() == false && !container.isRunning(),
                    "The listener should stop after the simulated failure");

            assertEquals(2L, committedOffset(admin, group));
            assertEquals(1, count("processed_run_event"));
            assertEquals(1, count("consumer_test_effect"));

            container.start();

            await(
                    () -> committedOffset(admin, group) == 3L,
                    "The failed record should succeed after restart");

            assertEquals(2, count("processed_run_event"));
            assertEquals(2, count("consumer_test_effect"));

            send(producer, "3", "{");

            await(
                    () -> !container.isRunning(),
                    "The listener should stop on malformed JSON");

            assertEquals(3L, committedOffset(admin, group));
            assertEquals(2, count("processed_run_event"));
            assertEquals(2, count("consumer_test_effect"));
            assertFalse(container.isRunning());
        } finally {
            container.stop();
        }
    }

    private static AdminClient admin() {
        return AdminClient.create(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers()));
    }

    private static KafkaProducer<String, String> producer() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers());
        properties.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        properties.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(properties);
    }

    private static void send(
            KafkaProducer<String, String> producer, String key, String payload)
            throws Exception {
        producer.send(new ProducerRecord<>(TOPIC, 0, key, payload))
                .get(30, TimeUnit.SECONDS);
    }

    private static String payload(UUID eventId, long runId) {
        return "{\"eventId\":\"" + eventId
                + "\",\"eventType\":\"RUN_COMPLETED\""
                + ",\"schemaVersion\":1"
                + ",\"runId\":" + runId
                + ",\"occurredAt\":\"2026-11-11T12:00:00Z\"}";
    }

    private static long committedOffset(AdminClient admin, String group) {
        try {
            Map<TopicPartition, OffsetAndMetadata> offsets =
                    admin.listConsumerGroupOffsets(group)
                            .partitionsToOffsetAndMetadata()
                            .get(5, TimeUnit.SECONDS);

            OffsetAndMetadata offset = offsets.get(PARTITION);
            return offset == null ? -1L : offset.offset();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking offsets", exception);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException exception) {
            return -1L;
        }
    }

    private static int count(String table) {
        return java.util.Objects.requireNonNull(
                jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
    }

    private static void await(BooleanSupplier condition, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200L);
        }
        assertTrue(condition.getAsBoolean(), message);
    }
}