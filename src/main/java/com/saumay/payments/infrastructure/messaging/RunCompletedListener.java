package com.saumay.payments.infrastructure.messaging;

import com.saumay.payments.infrastructure.persistence.JdbcRunEventProcessor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validates RUN_COMPLETED envelopes before invoking transactional intake. */
public final class RunCompletedListener {

    static final String TOPIC = "payments.run.completed.v1";

    private final JdbcRunEventProcessor processor;
    private final JsonMapper mapper;

    public RunCompletedListener(JdbcRunEventProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    @KafkaListener(
            id = "payments-run-completed-intake",
            groupId = "${payments.events.consumer-group:payments-case-intake-v1}",
            topics = TOPIC,
            containerFactory = "runEventKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record) {
        Objects.requireNonNull(record, "record");

        Event event = parse(record);
        processor.process(event.eventId(), event.runId());
    }

    private Event parse(ConsumerRecord<String, String> record) {
        if (!TOPIC.equals(record.topic()) || record.value() == null) {
            throw invalid();
        }

        try {
            JsonNode root = mapper.readTree(record.value());
            if (root == null || !root.isObject()) {
                throw invalid();
            }

            JsonNode eventIdNode = root.get("eventId");
            JsonNode typeNode = root.get("eventType");
            JsonNode versionNode = root.get("schemaVersion");
            JsonNode runIdNode = root.get("runId");
            JsonNode occurredAtNode = root.get("occurredAt");

            if (eventIdNode == null || !eventIdNode.isString()
                    || typeNode == null || !typeNode.isString()
                    || versionNode == null || !versionNode.isIntegralNumber()
                    || runIdNode == null || !runIdNode.isIntegralNumber()
                    || occurredAtNode == null || !occurredAtNode.isString()) {
                throw invalid();
            }

            String eventIdText = eventIdNode.stringValue();
            UUID eventId = UUID.fromString(eventIdText);
            long runId = runIdNode.longValue();

            if (!eventId.toString().equals(eventIdText)
                    || !"RUN_COMPLETED".equals(typeNode.stringValue())
                    || versionNode.longValue() != 1L
                    || runId <= 0
                    || !Long.toString(runId).equals(record.key())) {
                throw invalid();
            }

            Instant.parse(occurredAtNode.stringValue());
            return new Event(eventId, runId);
        } catch (RuntimeException exception) {
            // Do not include the raw event payload in the exception.
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid RUN_COMPLETED event");
    }

    private record Event(UUID eventId, long runId) {
    }
}