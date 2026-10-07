# Operational Runbook

Status: **Skeleton (Phase 0).** Section 1 is a complete procedure design for a stuck ingestion; commands marked `TBD` are filled in as the matching code lands in Phase 1 and verified during Phase 10. Nothing here claims behaviour the system does not yet have.

## 0. How to use this runbook

Each procedure follows the same shape: **symptom, impact, diagnose, fix, verify, escalate, prevent**. Every fix is safe to repeat (idempotent) unless stated.

Severity guide used across runbooks:

| Severity | Meaning | Example |
| --- | --- | --- |
| SEV1 | Controls bypassed or data integrity at risk | Audit verification fails |
| SEV2 | Daily reconciliation cannot complete | Ingestion stuck, run failing |
| SEV3 | Degraded but safe | Outbox backlog growing, one report slow |

## 1. Stuck or failed ingestion

### Symptom
An upload request never returns, returns a 5xx or a timeout, or the ingestion shows no counts after the expected duration. The business-date reconciliation cannot start because inputs are missing.

### Impact
SEV2. Nothing is wrongly matched, because a run needs completed ingestions. Risk is a missed SLA for the day's reconciliation.

### Key facts that make this safe
- A file is ingested in **one transaction** (ADR-0002). There is no half-ingested state to repair.
- Re-uploading identical bytes is safe: it returns the original ingestion with `duplicate: true` if it had committed, or starts clean if it had not.
- The advisory lock is transaction-scoped, so a dead connection releases it.

### Diagnose
1. Check the service is up: `GET /actuator/health` (TBD, Phase 1).
2. Find the ingestion by hash or id: `GET /api/v1/ingestions/{id}` or search by file hash (TBD).
3. Look at logs by `correlationId` and `ingestionId` (structured JSON). Look for: parse error, constraint violation, timeout, out of memory.
4. Check the database for a long-running ingestion transaction:
   ```sql
   SELECT pid, state, wait_event_type, wait_event, now() - xact_start AS xact_age, left(query, 80)
   FROM pg_stat_activity
   WHERE datname = current_database() AND state <> 'idle'
   ORDER BY xact_start;
   ```
5. Check for lock contention on the same file hash:
   ```sql
   SELECT locktype, mode, granted, pid FROM pg_locks WHERE locktype = 'advisory';
   ```
6. Check whether the file's rows exist (committed) or not:
   ```sql
   SELECT id, kind, accepted_count, rejected_count FROM ingestion_file WHERE file_hash = :hash;
   ```

### Decision table

| Observation | Meaning | Action |
| --- | --- | --- |
| `ingestion_file` row exists with counts | It committed; the client just never saw the response | Nothing to fix. Return the ingestion id to the requester. Re-upload would return `duplicate: true` |
| No row, no active transaction | Attempt died before commit; nothing persisted | Re-upload the file |
| No row, long active transaction, waiting on a lock | Another upload of identical bytes is in progress | Wait; the second request will return the first's result. If the holder is stuck beyond the timeout, go to the next row |
| Active transaction idle in transaction beyond the limit | Stuck holder | Terminate the backend (below), then re-upload |
| Many rejected lines, ingestion fails | Data-quality problem | Fix the source file; do not edit the database |
| Out of memory | File larger than the streaming parser budget | Check size limit; split by agreement with the source; raise heap only with an ADR |

Terminate a stuck backend only after confirming it is the ingestion transaction:
```sql
SELECT pg_terminate_backend(:pid);
```
Termination rolls the transaction back; no cleanup is needed.

### Fix
1. Apply the decision-table action.
2. Re-upload the same bytes. Do **not** modify the file to "make it a different hash" unless it genuinely is a corrected file.
3. Never insert or delete rows in `statement_line`, `ledger_entry` or `ingestion_file` by hand. If a bad file was committed, supersede it with a corrected file and handle the earlier lines as exceptions (a controlled, audited process, TBD).

### Verify
- `GET /api/v1/ingestions/{id}` shows expected accepted and rejected counts.
- Row counts for the date match the source's own control total (record count and sum, compared by the operator).
- Metric `statement_ingestion_duration_seconds` is back within the normal range (TBD, Phase 6).
- Start the reconciliation run and confirm it completes.

### Escalate
- To the platform on-call if a backend cannot be terminated, the database is unresponsive, or the same file fails twice.
- To the team lead if the day's SLA is at risk. Raise as SEV2.
- To security if a file contains an unexpected XML entity or DTD (refused by design; treat as an attempted attack).

### Prevent / follow-up
- File an RCA using [the template](../templates/rca-template.md) for any SEV2 or higher.
- Add a failing test reproducing the cause before fixing code.
- Update this runbook with the real commands once they exist.

## 2. Reconciliation run failed or incomplete (stub)

To be written in Phase 3 alongside the run service. Planned content: how to read run status and failure reason, why a failed run leaves no partial output (single transaction), how to re-run safely, how to replay a past run and compare the output hash.

## 3. Audit verification failed (stub, SEV1)

To be written in Phase 5. Planned content: freeze changes, run `audit verify` to find the first bad sequence number, compare with the externally anchored head hash from run output and logs, preserve evidence, escalate to security and the auditor, do **not** repair rows in place.

## 4. SLA breach spike (stub)

To be written in Phase 6. Planned content: reading the SLA and workload reports, rebalancing assignment, escalation path.

## 5. Outbox backlog growing (stub, Phase 7)

To be written in Phase 7. Planned content: check Kafka health, publisher status, backlog metric, safe restart of the publisher, consumer deduplication guarantees.
