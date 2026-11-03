# ADR-0010: Audit write path, scope and verification (extends ADR-0004)

- Status: **Proposed**
- Date: 2026-11-01
- Related: FR-09, FRD-09, FR-12, NFR-06, ADR-0004, ADR-0007, ADR-0009

## Context

ADR-0004 fixed the shape of the chain: one global chain, SHA-256 over a length-prefixed canonical encoding, microsecond timestamps, the event written in the same transaction as the change, a serialising lock, an append-only trigger and a verifier. Phase 4 has since built what has to write to it. Four details are still open: which actions are events, how sequence numbers are assigned, how states are stored so that a hash survives a database round trip, and where in a transaction the chain lock is taken.

## Decision

1. **Which actions are events.** `seq` is the event id.

| Entity type | Actions |
| --- | --- |
| `exception_case` | `CASE_ASSIGNED`, `CASE_INVESTIGATION_STARTED`, `CASE_ESCALATED`, `CASE_RESOLVED` (match, split, write-off or adjustment; the after-state names the resolution), `CASE_MATCH_REVERSED` |
| `approval_request` | `APPROVAL_REQUESTED`, `APPROVAL_APPROVED`, `APPROVAL_REJECTED`, `APPROVAL_DENIED` (a decision attempt refused for separation of duties) |
| `reconciliation_run` | `RUN_COMPLETED` (output hash and counts), `CASES_OPENED` (one event per intake call: run id, cases created, already open, SHA-256 of the sorted created case ids) |
| `configuration` | `CONFIG_CHANGED` |

   **Not events:** comments (append-only already, but free text), individual run matches (covered by the run's output hash), the per-case creation at intake (covered by `CASES_OPENED`), reads, and validation refusals. An intake call that creates nothing writes nothing. This is a stated limit, not an oversight.
2. **Same transaction.** The event is appended inside the transaction of the action. If the append fails the action rolls back: no change without evidence.
3. **Explicit sequence numbers.** `seq` is computed as head plus one under the chain lock, never from an identity column. An identity sequence skips values when a transaction rolls back, and the verifier would report that as a `SEQ_GAP` and call it tampering.
4. **States are canonical text, not `jsonb`.** `before_state` and `after_state` are `text` holding canonical JSON. `jsonb` reorders keys and reformats numbers, so a hash computed before storing would not match one computed after reading. This corrects the `jsonb` shown in ADR-0004's data model and in `architecture.md`. A small `CanonicalJson` in `domain` writes sorted keys, fixed string escaping, integers, booleans, null and arrays, and refuses any other type, so a floating-point value cannot enter an audited state.
5. **Versioned encoding.** A `scheme` column (1 for now) is the first input to the hash, so the encoding can change later without invalidating old events.
6. **Lock order.** The chain advisory lock is the **last** lock a transaction takes: after the case row lock, the run cut-off lock and the per-line claim locks, immediately before the insert, held until commit. Everyone takes it last, so it cannot take part in a deadlock. The cost is that all audited commits serialise on it; the hold time is measured in Phase 10, not assumed.
7. **Database guards.** A `BEFORE INSERT` trigger checks that `seq` equals the last `seq` plus one and that `previous_hash` equals the last `current_hash` (genesis is 64 zero hex characters). It cannot recompute SHA-256 and does not try: hash correctness is the verifier's job. `UPDATE`, `DELETE` and `TRUNCATE` are rejected by triggers. The deployment runbook documents a database role with only `INSERT` and `SELECT`; the tests run as the owner, so the triggers are the control that is actually tested.
8. **Verification.** `AuditVerifier` reads events in `seq` order in pages, recomputes every hash and checks every link. It returns `OK(count, headSeq, headHash)` or `FAIL(firstBadSeq, reason)` with `HASH_MISMATCH`, `CHAIN_BREAK` or `SEQ_GAP`. It is available as `GET /api/v1/audit/verify` and as a command line mode of the same jar (`--audit.verify`, exit code 0 for OK, 3 for FAIL). An optional expected head (`seq:hash`) catches deletion of the **tail**, which a chain cannot detect by itself.
9. **Anchor.** A structured log line `audit.head seq=<n> hash=<h>` is written when a run completes and after each verification, so the head also lives outside the table (ADR-0004 point 8).
10. **`case_activity` stays** as the per-case timeline shown in the case view. The case store writes the timeline row and the audit event through one code path, and a test asserts that every action produced both. The timeline is a read model; the chain is the evidence.
11. **History.** `GET /api/v1/audit/{entityType}/{entityId}` pages the events of one entity by `seq`, backed by an index on `(entity_type, entity_id, seq)`.

## Alternatives considered

| Alternative | Why rejected |
| --- | --- |
| Identity column for `seq` | Gaps on rollback look like tampering |
| `jsonb` for states | Not byte-stable, so hashes can drift on a round trip |
| Hash in SQL (`pgcrypto`) or audit rows written by database triggers | Moves the evidence logic into the database where it is harder to test, and a trigger cannot know the acting user |
| Audit in a separate transaction after the action | A crash between the two loses the evidence of a committed change |
| One event per case at intake | Tens of thousands of serialised appends behind one lock for a large run |
| Dropping `case_activity` and deriving the timeline from the chain | Couples a read feature to the evidence table and makes comments awkward |

## Consequences

- Every case action, approval and configuration change is provable and its tamper position identifiable (NFR-06). The tamper test edits one row (with the guard trigger disabled in the test) and expects a failure at exactly that `seq`.
- Concurrent appends stay linear with no gaps (AC-09.7), tested with many threads.
- Audited commits serialise: a throughput ceiling that Phase 10 measures.
- Honest limits: a superuser who rewrites the whole chain defeats it unless the head is anchored outside; the tail can be cut off undetected unless an expected head is supplied; comments are not tamper-evident.
- The encoding is a compatibility contract, versioned by `scheme`.

## Interview explanation

"Every state change writes an audit event in the same transaction, so there is never a change without evidence. Each event hashes the one before it, so editing history breaks every later hash and the verifier names the first bad row. Two details I got right on purpose: sequence numbers are assigned under the chain lock rather than by a database sequence, because rollbacks would otherwise leave gaps that look like tampering, and states are stored as canonical text because `jsonb` doesn't preserve the bytes I hashed."