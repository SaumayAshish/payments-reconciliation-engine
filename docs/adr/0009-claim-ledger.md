# ADR-0009: Claim ledger for matched lines

## Status

Accepted

## Date

2026-10-26

## Related

FRD-05 (AC-05.4), FRD-10 (Q-07), FRD-12 (AC-12.3, AC-12.4), ADR-0003, ADR-0007.

## Context

Q-07 says a run's candidates are those "not already confirmed-matched by an earlier run", and AC-05.4 says a
candidate claimed by one match cannot be claimed by another "in the same or a later run until unmatched". Phase 3
enforces this only inside one run: `loadInputs` reads every ledger entry up to the run's cut-off and every line of its
statement batches, so a ledger entry auto-confirmed on Monday can be matched again on Tuesday. Manual matching,
splitting and unmatching (FRD-12) make the gap wider, because they claim and release lines after the run has finished.

Whatever fixes this must keep a replay deterministic: a completed run, recomputed from its stored inputs, must see
exactly what it saw the first time, even though later matches and unmatches have changed which lines are free.

## Decision

**In short.** Record every claim and release of a line as an append-only row in `line_claim`, give each run a
`claim_cutoff_seq` fixed when it is created, and have `loadInputs` leave out lines whose claim was active at that cut-off.

1. **Append-only ledger.** `line_claim` holds `CLAIM` and `RELEASE` rows, each with its own sequence number. A release
   names the claim it releases (`releases_seq`, unique), so nothing is ever updated or deleted. Q-07's "unmatch writes
   a reversal row with its own sequence number" is exactly this.
2. **One active claim per line, enforced by the database.** A trigger refuses a claim on a line that already has an
   unreleased claim (SQLSTATE 23505) and a release that does not name a claim on the same line (23514). It takes a
   per-line advisory lock first, so two concurrent writers cannot both pass the check. The rule holds for any writer,
   including one that skips the application.
3. **Both sides.** Statement lines and ledger entries are claimed, so re-running a statement batch cannot re-match
   lines that are already matched.
4. **Run cut-off.** A run records `claim_cutoff_seq`, the highest claim sequence when it was created, under the same
   exclusive cut-off lock as `ledger_cutoff_seq`. `loadInputs` excludes lines whose latest claim at or below that
   cut-off is unreleased at or below that cut-off. The run's own claims are written later and so have higher
   sequence numbers; a replay therefore sees what the original run saw, and the output hash reproduces.
5. **Who claims.** Auto-confirmed run matches claim their lines in the same transaction that completes the run.
   Review-band proposals do not: they are suggestions until a person resolves them. A manual match or split claims at
   resolution. An unmatch releases the claims and opens a new linked case (Q-08).
6. **Claim writers take the shared cut-off lock** (the one ingestion already takes), so a run creating its cut-off
   cannot miss a claim that has a lower sequence number but has not committed yet.
7. **Older runs are unaffected.** Existing runs get `claim_cutoff_seq = 0` and there are no claims before V5.

## Alternatives considered

- **A mutable `claimed` flag on each line.** Rejected: it changes input evidence, and a replay could not see what the
  original run saw.
- **Derive claims from `run_match_line` at read time.** Rejected: manual matches and unmatches have no run rows, and
  the query would scan every earlier run.
- **Store the excluded lines on each run.** Rejected: large, duplicates data the ledger already holds.
- **A timestamp cut-off instead of a sequence.** Rejected: clock skew and ties make it non-deterministic.

## Consequences

- Phase 3 behaviour changes: a later run no longer re-matches lines an earlier run auto-confirmed. This is recorded as
  a Phase 3 deviation fixed in Phase 4, with a test.
- Two runs that overlap and both auto-confirm the same line: the one that completes second fails safely (its claim is
  refused, the run is marked FAILED) instead of producing a double match. Running reconciliations one after another
  is the intended use.
- The claim table grows with every match; both side indexes are partial for that reason.
- The per-line lock is hashed into 1,024 buckets (V6). One advisory lock per line would exhaust PostgreSQL's lock
  table when a large run claims tens of thousands of lines in one transaction. With buckets, two writers on the
  same line still collide and unrelated lines at worst share a bucket. Two bulk transactions that lock buckets in
  a different order can deadlock; PostgreSQL aborts one, which fails safely (a run ends FAILED).

## Interview explanation

"A line can be matched once. I enforce that with an append-only claim ledger and a trigger, not a flag, so history is
never rewritten and the rule holds even if an application bug skips it. Each run records how much of the ledger it
could see, which is what keeps replay deterministic after later matches and unmatches."

## Amendment 2026-10-30 (migration V7)

Unmatching an auto-confirmed run match has no case to link to, because auto-confirmed matches never had one. The original constraint required every `UNMATCH` case to link to a case, so that unmatch could not be recorded. Migration V7 relaxes `ck_case_origin`: a `RUN` case still names its run and ordinal and has no linked case; an `UNMATCH` case names no run, and its linked case is optional. The claim ledger itself is unchanged: the unmatch still writes `RELEASE` rows, nothing is deleted, and the run's stored result and hash stay valid for replay.