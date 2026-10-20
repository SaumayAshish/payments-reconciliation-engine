package com.saumay.payments.ingestion.parse;

import java.util.Objects;

/** A parsed ledger JSON body: its rows, and the canonical bytes that define the file's identity. */
public record LedgerJsonDocument(ParsedFile parsed, byte[] canonicalBytes) {

    public LedgerJsonDocument {
        Objects.requireNonNull(parsed, "parsed");
        Objects.requireNonNull(canonicalBytes, "canonicalBytes");
    }
}