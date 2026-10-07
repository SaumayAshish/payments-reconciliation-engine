# ADR-0002: Idempotent ingestion by content hash

- Status: **Accepted** (approved by Caesar, 2026-10-07)
- Date: 2026-10-07
- Related: FR-01, FRD-01, NFR-03, Q-11

## Context

File uploads are retried: networks drop, operators double-click, schedulers re-fire, processes are killed mid-load. If each attempt creates rows, a bank statement is counted twice and the reconciliation produces phantom breaks or, worse, phantom matches.

## Decision

1. The identity of an ingestion is `(kind, SHA-256(raw bytes))` where `kind` is `STATEMENT` or `LEDGER`. The hash is computed over the bytes exactly as received, before any decoding or normalisation.
2. `ingestion_file` has `UNIQUE (kind, file_hash)`. This constraint is the final backstop; correctness never depends only on application logic.
3. Each file is ingested in **one database transaction**. A crash before `COMMIT` leaves no rows, so a retry begins from a clean state. There is no "half-ingested" status to clean up.
4. Concurrent identical uploads are serialised with `pg_advisory_xact_lock(hash64(kind, fileHash))`. The loser, after acquiring the lock, finds the existing row and returns it.
5. A duplicate upload returns **200 OK with the original ingestion and `duplicate: true`** and inserts nothing (Q-11). Retried requests therefore behave like safe retries of a PUT, which is what clients and schedulers expect.
6. REST ledger submissions hash a **canonical serialisation** (fixed field order, no insignificant whitespace) so semantically identical JSON is recognised as the same input.
7. Line identity within a file is `lineKey = hex(SHA-256(fileHash + ":" + lineNumber))`: deterministic, never random.
8. Lines are **not** deduplicated by content across or within files, because two genuine identical payments are legitimate. Suspected duplicates are flagged (`DUPLICATE_SUSPECT`) and surfaced, not dropped.

## Alternatives considered

| Alternative | Why rejected |
| --- | --- |
| Filename as identity | Names are reused and renamed; no integrity guarantee |
| Row-level dedup by business key | Would silently delete legitimate identical payments |
| Client-supplied idempotency key only | Pushes correctness to every caller; kept as an optional later addition, not a substitute |
| Chunked, resumable ingestion with a status column | More states, more recovery code, and a million-line file fits comfortably in one transaction. Revisit only if measurement says otherwise |

## Consequences

- Re-uploading any file N times leaves counts unchanged (NFR-03), including after a kill mid-ingest (AC-01.5).
- A different byte sequence for the same business content (re-export, BOM, line endings) is a different file. This is deliberate and documented; overlapping re-exports are handled by duplicate detection downstream (FRD-04 EC-04.5), not by pretending bytes are equal.
- A very large transaction holds locks and WAL for its duration. Mitigated by batched inserts and measurement in Phase 10.

## Interview explanation

"Uploads get retried, so the system must treat the same input as the same input. I hash the raw bytes, enforce uniqueness in the database, do the whole file in one transaction so a crash leaves nothing behind, and lock on the hash so concurrent retries collapse into one."
