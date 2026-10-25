# ADR-0007: Exception case model and state machine

- Status: Accepted
- Date: 2026-10-25
- Related: FR-06, FR-07, FR-12; FRD-06, FRD-07, FRD-12; Q-08, A-02

## Context

Run results (V3) are append-only evidence: triggers reject updates and deletes. Analysts need mutable workflow on top of them: an owner, a status, comments, a timeline. The FRD requires one queue for review items and breaks (A-02), no duplicate cases when a run is re-executed (AC-06.3), exactly eight legal transitions (FRD-07), a conflict when two users act at once (AC-07.4) and no reopen (Q-08).

## Decision

1. **A separate mutable table, `exception_case`.** `run_exception` is never updated. A case records its origin (`origin_run_id`, `origin_ordinal`) and copies the `natural_key`.
2. **Case intake is its own step after a run is `COMPLETED`.** For every `run_exception` whose natural key has no run-origin case, insert one case. A partial unique index on `natural_key` where `origin = 'RUN'` makes the step idempotent and safe to retry. The run itself is not touched, so EC-06.2 holds: a failed run creates no cases.
3. **REVIEW or BREAK is derived**, not stored from the engine: `LOW_CONFIDENCE` and `AMBIGUOUS_TIE` are `REVIEW`; every other kind is `BREAK`.
4. **The state machine is one table in the domain**, with exactly the eight rows of FRD-07. Anything else raises `ILLEGAL_STATE_TRANSITION`. A database trigger validates the same pairs as a backstop. A test enumerates all 25 ordered pairs and asserts exactly 8 succeed (AC-07.5).
5. **Optimistic locking** with a `version` column. A stale write returns `CASE_VERSION_CONFLICT` (409). A system escalation on an already escalated case is a no-op (EC-07.2).
6. **Append-only history tables:** `case_activity` (the timeline), `case_assignment` (owner history, so the previous owner is retained, AC-07.3), `case_comment` and `case_attachment` (metadata only: name, size, SHA-256; no file bytes).
7. **Unmatch opens a new case** with `origin = 'UNMATCH'` and `linked_case_id` pointing at the resolved one. The resolved case is never changed (Q-08, AC-12.3).
8. **Phase 4 resolves by manual match or split only.** Adjustment and write-off arrive in Phase 5 with maker-checker.
9. **Actors are plain strings until Phase 8.** Time comes from an injected `Clock` in the application layer; the domain never reads the clock.

**In short.** workflow state lives beside the evidence, not inside it. replay and the output hash depend on run results never changing.

## Alternatives considered

- Rejected: add status and owner columns to `run_exception`. That needs the immutability trigger removed and puts mutable data under the hash.

## Consequences

- AC-07.1 and AC-09.1 (an audit event in the same transaction as every state change) cannot be met until Phase 5. Phase 4 writes `case_activity` in the same transaction, and Phase 5 adds the audit append inside the same service methods. This is recorded as known debt.
- Phase 5 must make a written-off line leave the matching pool, otherwise every rerun re-finds it.
- Candidates shown on a review case are the ones Phase 3 stored (the proposed match), not the top 3 of AC-05.2. This is a recorded deviation.

## Interview explanation

"Run results are evidence; cases are workflow. I kept them in separate tables so a rerun can never rewrite history."
