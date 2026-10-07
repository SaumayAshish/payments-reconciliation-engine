# API

Status: **Endpoint catalogue (Phase 0).** The authoritative contract will be an OpenAPI 3.1 file, written **before** each endpoint group is implemented (`docs/api/openapi.yaml`, started in Phase 1 with ingestion). This catalogue fixes names, roles and idempotency behaviour so the specs and tests agree.

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
- Optimistic concurrency on cases via `version` in the body or `If-Match`; conflicts return `409 CONCURRENT_MODIFICATION`.

## Catalogue

| Method and path | Purpose | Roles | Idempotency | FRD | Phase |
| --- | --- | --- | --- | --- | --- |
| `POST /ingestions/statements` | Upload statement (CSV or camt.053) | `ADMIN` | By content hash; duplicate returns `200` with `duplicate: true` | 01, 02 | 1 |
| `POST /ingestions/ledger` | Submit ledger entries (JSON) or CSV | `ADMIN` | By content hash of canonical form | 03 | 1 |
| `GET /ingestions/{id}` | Counts, rejected lines summary | `ADMIN`, `ANALYST`, `AUDITOR` | Safe | 01 | 1 |
| `GET /ingestions/{id}/rejected-lines` | Paged rejected lines with error codes | `ADMIN`, `ANALYST` | Safe | 02 | 1 |
| `POST /reconciliations/runs` | Start a run for a business date | `ADMIN` | Same `(date, config, cutoff, batches)` returns the existing run | 04, 10 | 3 |
| `GET /reconciliations/runs/{id}` | Run status, counts, output hash | `ADMIN`, `ANALYST`, `AUDITOR` | Safe | 10 | 3 |
| `GET /reconciliations/runs/{id}/results` | Paged matches and exceptions | `ADMIN`, `ANALYST`, `AUDITOR` | Safe | 04, 05 | 3 |
| `POST /reconciliations/runs/{id}/replay` | Recompute and compare output hash | `ADMIN`, `AUDITOR` | Safe (read-only) | 10 | 3 |
| `GET /exceptions` | Queue, filter by team, state, type, priority, ageing | `ANALYST`, `TEAM_LEAD`, `CONTROLLER` | Safe | 06, 11 | 4 |
| `GET /exceptions/{id}` | Case detail, candidates, timeline | `ANALYST`, `TEAM_LEAD`, `CONTROLLER`, `AUDITOR` | Safe | 06 | 4 |
| `POST /exceptions/{id}/assign` | Assign owner | `TEAM_LEAD` | Same owner is a no-op | 07 | 4 |
| `POST /exceptions/{id}/start` | `ASSIGNED` to `INVESTIGATING` | `ANALYST` (owner) | State-guarded | 07 | 4 |
| `POST /exceptions/{id}/comments` | Add note | `ANALYST`, `TEAM_LEAD` | Client request id | 07 | 4 |
| `POST /exceptions/{id}/escalate` | Escalate with reason | `ANALYST`, `TEAM_LEAD` | State-guarded | 07 | 4 |
| `POST /exceptions/{id}/match` | Manual match | `ANALYST` | State-guarded | 12 | 4 |
| `POST /exceptions/{id}/split` | Split into components | `ANALYST` | State-guarded | 12 | 4 |
| `POST /exceptions/{id}/unmatch` | Reverse a confirmed match, create linked case | `ANALYST`, `TEAM_LEAD` | State-guarded | 12 | 4 |
| `POST /exceptions/{id}/resolve` | Resolve; submits an approval if controlled | `ANALYST` | State-guarded | 07, 08 | 4 to 5 |
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

- The exception endpoint names follow the master prompt. `start` and `comments` are added because the state machine and timeline need them.
- `POST /ingestions/*` returns `200` for duplicates, not `409` (Q-11). Provide your decision before the OpenAPI file is written.
- A CLI command (`audit verify`) is provided alongside the endpoint so verification does not depend on the HTTP layer.
