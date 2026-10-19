package com.saumay.payments.ingestion;

/** The wire format a file arrived in. It does not affect identity: identity is (kind, content hash). */
public enum FileFormat {
    CSV,
    CAMT053,
    JSON
}