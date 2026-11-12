package com.saumay.payments.application;

/** Sends one event and returns only after the transport acknowledges it. */
public interface EventTransport {

    void send(String topic, String key, String payload) throws InterruptedException;
}