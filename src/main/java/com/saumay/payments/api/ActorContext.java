package com.saumay.payments.api;

/** Turns what the caller presented into the acting user, or refuses (ADR-0011). Phase 8 replaces the adapters. */
public interface ActorContext {

    Actor resolve(String header);
}