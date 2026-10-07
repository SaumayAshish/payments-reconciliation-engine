# ADR-0004: Append-only, hash-chained audit log

- Status: **Proposed** (Phase 0, awaiting approval)
- Date: 2026-10-07
- Related: FR-09, FRD-09, NFR-06, Q-13

## Context

Auditors ask first: who changed what, when, and can you prove nobody altered the record afterwards? Access control alone does not prove integrity, because a privileged person or a bug can change a row. We need evidence that makes alteration **detectable** and says exactly **where**.

## Decision

1. **Single global chain.** One `audit_event` table with a strictly increasing `seq`. Event `n` stores `previous_hash = current_hash(n-1)`; the genesis event uses 32 zero bytes.
2. **Hash definition.** `current_hash = SHA-256( canonicalBytes(event) || previous_hash )` where `canonicalBytes` is a length-prefixed encoding of, in fixed order: `seq`, `entityType`, `entityId`, `actor`, `action`, `beforeState`, `afterState`, `occurredAtMicros`. States are canonical JSON (sorted keys, no whitespace). Length prefixes prevent ambiguity between adjacent fields.
3. **Timestamps** are stored and hashed as epoch **microseconds** (`bigint`), matching PostgreSQL's `timestamptz` precision so a round trip cannot change the hash.
4. **Same transaction.** The audit event is appended in the same database transaction as the change it records (AC-09.1). No change without evidence, no evidence without a change.
5. **Serialised appends.** A transaction-scoped advisory lock around "read head, insert next" keeps the chain linear with no forks (AC-09.7). Audit volume is human-scale (state changes, approvals, config, run summaries), so contention is acceptable. Per-line automatic matches are **not** individual audit events; they are covered by the run's `outputHash` recorded in the `RUN_COMPLETED` event.
6. **Append-only at the database.** A trigger raises on `UPDATE` and `DELETE`, and the application role has `INSERT` and `SELECT` only (AC-09.6). The chain does not rely on trust in the application.
7. **Verification.** `audit verify` streams events in `seq` order, recomputes each hash, checks the link to the previous event and the absence of gaps. It reports `OK(count, headHash)` or `FAIL(firstBadSeq, reason)` with `reason` one of `HASH_MISMATCH`, `CHAIN_BREAK`, `SEQ_GAP`.
8. **External anchoring.** The head hash is written into each run's output and into the application log, so rewriting the whole chain would also require rewriting data held elsewhere (EC-09.3).

## Alternatives considered

| Alternative | Why rejected |
| --- | --- |
| Per-entity chains | Cheaper concurrency, but cannot prove that no entity's history was dropped wholesale, and a missing chain is not detectable |
| Ordinary audit table with triggers only | Detects nothing if a privileged user edits rows |
| Merkle tree with periodic roots | Stronger and scalable, but heavier than v1 needs; the linear chain is easier to explain and demo. Listed as a future option |
| External ledger or blockchain | Pure resume decoration. Rejected |
| Event sourcing the whole system | Large architectural commitment for a problem that an audit table solves |

## Consequences

- Editing any stored field, deleting an event or inserting one in the middle breaks verification at an identifiable position (NFR-06). The tamper test is a README evidence item.
- Honest limit: a database superuser who recomputes every subsequent hash defeats the chain **by itself**. The external anchor and database privilege separation reduce this; they do not eliminate it. The docs say so.
- Verification is O(n). Fine for audit volumes; a checkpoint mechanism can be added later.
- The canonical encoding is a compatibility contract. Changing it requires a versioned scheme.

## Interview explanation

"Access control tells you who was allowed; a hash chain lets you prove nothing was altered. Each event commits to the previous one, so changing history breaks every later hash and the verifier points at the first bad row. I'm explicit about the limit: a superuser who rewrites the whole chain defeats it unless the head is anchored elsewhere, which is why I record it outside the table too."
