package com.saumay.payments.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.saumay.payments.infrastructure.persistence.JdbcRunEventProcessor;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class RunCompletedListenerTest {

    private static final String ID = "6f6a70d1-c552-4af2-9de9-08c94cfc5705";

    @Test
    void validEventInvokesProcessor() {
        JdbcRunEventProcessor processor = mock(JdbcRunEventProcessor.class);
        RunCompletedListener listener = new RunCompletedListener(processor);

        listener.onMessage(record("42", validPayload()));

        verify(processor).process(UUID.fromString(ID), 42L);
    }

    @Test
    void wrongKeyDoesNotInvokeProcessor() {
        JdbcRunEventProcessor processor = mock(JdbcRunEventProcessor.class);
        RunCompletedListener listener = new RunCompletedListener(processor);

        assertThrows(IllegalArgumentException.class,
                () -> listener.onMessage(record("43", validPayload())));

        verify(processor, never()).process(UUID.fromString(ID), 42L);
    }

    @Test
    void duplicateJsonFieldIsRejected() {
        JdbcRunEventProcessor processor = mock(JdbcRunEventProcessor.class);
        RunCompletedListener listener = new RunCompletedListener(processor);
        String duplicate = validPayload().replace(
                "\"runId\":42",
                "\"runId\":42,\"runId\":43");

        assertThrows(IllegalArgumentException.class,
                () -> listener.onMessage(record("42", duplicate)));

        verify(processor, never()).process(UUID.fromString(ID), 42L);
    }

    @Test
    void unsupportedVersionAndMalformedJsonAreRejected() {
        JdbcRunEventProcessor processor = mock(JdbcRunEventProcessor.class);
        RunCompletedListener listener = new RunCompletedListener(processor);

        String versionTwo = validPayload().replace(
                "\"schemaVersion\":1", "\"schemaVersion\":2");

        assertThrows(IllegalArgumentException.class,
                () -> listener.onMessage(record("42", versionTwo)));
        assertThrows(IllegalArgumentException.class,
                () -> listener.onMessage(record("42", "{")));

        verify(processor, never()).process(UUID.fromString(ID), 42L);
    }

    @Test
    void processorFailurePropagatesToTheListenerContainer() {
        JdbcRunEventProcessor processor = mock(JdbcRunEventProcessor.class);
        RunCompletedListener listener = new RunCompletedListener(processor);

        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable"))
                .when(processor).process(UUID.fromString(ID), 42L);

        assertThrows(IllegalStateException.class,
                () -> listener.onMessage(record("42", validPayload())));
    }

    private static ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>(RunCompletedListener.TOPIC, 0, 0L, key, value);
    }

    private static String validPayload() {
        return "{\"eventId\":\"" + ID
                + "\",\"eventType\":\"RUN_COMPLETED\""
                + ",\"schemaVersion\":1"
                + ",\"runId\":42"
                + ",\"occurredAt\":\"2026-11-11T12:00:00Z\"}";
    }
}