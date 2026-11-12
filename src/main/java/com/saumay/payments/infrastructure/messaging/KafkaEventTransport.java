package com.saumay.payments.infrastructure.messaging;

import com.saumay.payments.application.EventTransport;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;

/** Sends a keyed event and waits for broker acknowledgement. */
public final class KafkaEventTransport implements EventTransport {

    private final KafkaTemplate<String, String> kafka;

    public KafkaEventTransport(KafkaTemplate<String, String> kafka) {
        this.kafka = Objects.requireNonNull(kafka, "kafka");
    }

    @Override
    public void send(String topic, String key, String payload) throws InterruptedException {
        try {
            kafka.send(topic, key, payload).get(20, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException failed) {
            throw new IllegalStateException("Kafka did not acknowledge the event", failed);
        }
    }
}