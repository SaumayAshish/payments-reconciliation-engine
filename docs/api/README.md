# API

Status: **Endpoint catalogue, updated as endpoints ship.** The exception endpoints (Phase 4) are implemented and specified in `openapi.yaml`, which is the authoritative contract; this table fixes names, roles and idempotency behaviour. Roles shown are the ones Phase 8 will enforce.

## Conventions

- Base path `/api/v1`. DTOs only; entities are never exposed.
- JSON, UTF-8. Money in responses as `{ "amountMinor": 125050, "currency": "GBP" }`. Display formatting is a client concern.
- Every request carries or is assigned a `X-Correlation-Id`, echoed in the response and in logs.
- Errors use one shape:
  ```json
  { "code": "ILLEGAL_STATE_TRANSITION", "message": "Cannot move case from RESOLVED to OPEN",
    "correlationId": "…", "timestamp": "2026-10-07T15:04:05.123456Z",
    "details": [ { "field": "state", "issue": "terminal state" } ] }
  ```
  No stack traces, SQL, hostnames or secrets, ever.
- Roles: `ADMIN`, `ANALYST`, `TEAM_LEAD`, `CONTROLLER`, `AUDITOR`. Enforced server-side from Phase 8; an interim `ActorContext` supplies identity before then (Q-13).
- Optimistic concurrency on cases: every write names the `expectedVersion` it last read; a stale version returns `409 CASE_VERSION_CONFLICT` and changes nothing. Until Phase 8 writes also carry an `X-Actor` header (`400 ACTOR_REQUIRED` when missing).

## Catalogue

| Method and path | Purpose | Roles | Idempotency | FRD | Phase |
| --- | --- | --- | --- | --- | --- |
| `POST /ingestions/statements` | Upload statement (CSV or camt.053) | `ADMIN` | By content hash; duplicate returns `200` with `duplicate: true` | 01, 02 | 1 |
| `POST /ingestions/ledger` | Submit ledger entries (CSV or JSON)| `ADMIN` | By content hash of canonical form | 03 | 1 |
| `GET /ingestions/{id}` | Counts, rejected lines summary | `ADMIN`, `ANALYST`, `AUDITOR` | Safe | 01 | 1 |
| `GET /ingestions/{id}/rejected-lines` | Paged rejected lines with error codes | `ADMIN`, `ANALYST` | Safe | 02 | later (the first 1000 rejected lines are already in GET /ingestions/{id}) |
| `POST /reconciliations/runs` | Start a run for a business date | `ADMIN` | Same `(date, config, cutoff, batches)` returns the existing run | 04, 10 | 3 |
| `GET /reconciliations/runs/{id}` | Run status, counts, output hash | `ADMIN`, `ANALYST`, `AUDITOR` | Safe | 10 | 3 |
| `GET /reconciliations/runs/{id}/results` | Paged matches and exceptions | `ADMIN`, `ANALYST`, `AUDITOR` | Safe | 04, 05 | 3 |
| `POST /reconciliations/runs/{id}/replay` | Recompute and compare output hash | `ADMIN`, `AUDITOR` | Safe (read-only) | 10 | 3 |
| `POST /reconciliations/runs/{id}/exceptions` | Open a case for every exception of a completed run | `ADMIN`, `TEAM_LEAD` | Safe to repeat: one case per natural key, repeat creates none | 06 | 4 (done; Phase 7 replaces it with an event consumer) |
| `POST /reconciliations/runs/{runId}/matches/{ordinal}/unmatch` | Reverse an auto-confirmed run match, open a review case | `ADMIN`, `TEAM_LEAD` | `409 NOTHING_TO_UNMATCH` when already reversed | 12 | 4 (done) |
| `GET /exceptions` | Queue; filter by status, team, priority, kind, owner; keyset paging (`after`, `limit` up to 500) | `ADMIN`, `ANALYST`, `TEAM_LEAD`, `CONTROLLER`, `AUDITOR` | Safe | 06 | 4 (done) |
| `GET /exceptions/{id}` | Case detail: timeline, comments, assignments, claim rows, one consistent snapshot (candidate matches deferred) | `ADMIN`, `ANALYST`, `TEAM_LEAD`, `CONTROLLER`, `AUDITOR` | Safe | 06 | 4 (done) |
| `POST /exceptions/{id}/assign` | Assign or reassign an owner | `ADMIN`, `TEAM_LEAD` | Version-guarded | 07 | 4 (done) |
| `POST /exceptions/{id}/investigate` | `ASSIGNED` to `INVESTIGATING` (needs an owner) | `ADMIN`, `ANALYST`, `TEAM_LEAD` | Version-guarded | 07 | 4 (done) |
| `POST /exceptions/{id}/comments` | Add a note; appears on the timeline | `ADMIN`, `ANALYST`, `TEAM_LEAD` | Append-only | 07 | 4 (done) |
| `POST /exceptions/{id}/escalate` | `INVESTIGATING` to `ESCALATED`, reason mandatory | `ADMIN`, `TEAM_LEAD` | Version-guarded | 07 | 4 (done) |
| `POST /exceptions/{id}/match` | Resolve by manual match (1:1) or split (1:n or n:1, up to 8 a side, sums equal) | `ADMIN`, `ANALYST`, `TEAM_LEAD` | Version-guarded; claimed line returns `409` | 12 | 4 (done) |
| `POST /exceptions/{id}/unmatch` | Reverse a manual match or split, open a linked review case | `ADMIN`, `TEAM_LEAD` | A second unmatch returns `409 NOTHING_TO_UNMATCH` | 12 | 4 (done) |
| `POST /exceptions/{id}/resolve` | Write-off or adjustment resolution; submits an approval | `ANALYST` | State-guarded | 07, 08 | 5 |
| `POST /approvals/{id}/approve` | Approve (checker must differ from maker) | `CONTROLLER` | Decided once | 08 | 5 |
| `POST /approvals/{id}/reject` | Reject with reason | `CONTROLLER` | Decided once | 08 | 5 |
| `GET /approvals` | Pending approvals queue | `CONTROLLER` | Safe | 08 | 5 |
| `GET /audit/{entityType}/{entityId}` | Entity history | `AUDITOR`, `ADMIN` | Safe | 09 | 5 |
| `GET /audit/verify` | Verify the chain, return `OK` or first bad `seq` | `AUDITOR`, `ADMIN` | Safe | 09 | 5 |
| `GET /reports/match` | Match and tier distribution | `TEAM_LEAD`, `AUDITOR`, `ADMIN` | Safe | 11 | 6 |
| `GET /reports/ageing` | Ageing buckets, `asOf` parameter | `TEAM_LEAD`, `AUDITOR`, `ADMIN` | Safe | 11 | 6 |
| `GET /reports/sla` | SLA status | `TEAM_LEAD`, `AUDITOR`, `ADMIN` | Safe | 11 | 6 |
| `GET /reports/workload` | Team workload | `TEAM_LEAD`, `ADMIN` | Safe | 11 | 6 |
| `GET /config`, `PUT /config` | Read and create a new configuration version | `ADMIN` | New version each change, audited | 05, 09 | 3 |

## Notes

- The exception endpoints follow the master prompt with three deliberate changes: `start` is `investigate`; there is no separate `split` (a one-to-many `match` is a split); and there is no `resolve` until Phase 5, because in Phase 4 a case resolves only through a match or split, and write-off or adjustment needs maker-checker.
- `POST /ingestions/*` returns `201` for a new ingestion and `200` with `duplicate: true` for repeated bytes (Q-11, decided). The body is the raw file; see [openapi.yaml](openapi.yaml) for content types, error codes and examples.
- A CLI command (`audit verify`) is provided alongside the endpoint so verification does not depend on the HTTP layer.
