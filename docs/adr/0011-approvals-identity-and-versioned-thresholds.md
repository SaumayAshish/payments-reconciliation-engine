# ADR-0011: Approvals, write-off and adjustment, interim identity, versioned thresholds

- Status: **Proposed**
- Date: 2026-11-01
- Related: FR-08, FRD-08, FR-09, FR-12 (AC-12.5), Q-09, Q-13, ADR-0007, ADR-0009, ADR-0010

## Context

FRD-08 requires a second approver for write-offs and adjustments above a threshold, and a checker who is not the maker. Today every caller names itself in an `X-Actor` header, roles do not exist, the threshold has no home, and a case can only resolve by match or split. Authentication arrives in Phase 8, but separation of duties has to be real and testable before then.

## Decision

### Identity until Phase 8 (Q-13)
- An `ActorContext` port in `application` yields an `Actor(id, roles)`. Ids are trimmed and lower-cased, so `Alice` and `alice` cannot pass as two people.
- The only implementation is a **dev user registry**: `X-Actor` is looked up in configured users and roles (alice ANALYST, bob TEAM_LEAD, carol and dave CONTROLLER, erin AUDITOR, root ADMIN). A missing header stays `400 ACTOR_REQUIRED`; an unknown id is `401 UNKNOWN_ACTOR`.
- It is active only under the `dev` profile, which is the default for local runs. Under any other profile the application **refuses to start** until an authentication-backed implementation exists (Phase 8). Fail closed.
- In Phase 5 only the new controlled endpoints check roles (submit: ANALYST, TEAM_LEAD or ADMIN; approve and reject: CONTROLLER; configuration: ADMIN; audit read: AUDITOR or ADMIN). Authorisation of the remaining endpoints is Phase 8.

### Controlled actions
- `WRITE_OFF` and `ADJUSTMENT`, through one endpoint, `POST /api/v1/exceptions/{id}/resolve`, with `resolutionType`, `reason`, `expectedVersion` and, for an adjustment, `statementSeqs` and `ledgerSeqs`.
- **Write-off** resolves the case with no counterpart; the controlled amount is the case amount. **Adjustment** matches lines like a manual match, but the two totals may differ in the same currency; the controlled amount is the absolute difference, which must not be zero (a zero difference is a plain match). The reason is mandatory.
- Controlled means the absolute controlled amount is **strictly greater** than the threshold for the currency, with a default for unlisted currencies (Q-09). At or below it the owner resolves directly: `200`, case `RESOLVED`, audited. Above it: `202`, an `approval_request` is created `PENDING`, the case stays `INVESTIGATING`.
- Written-off and adjusted cases cannot be unmatched in Phase 5. Reversing a financial write-off is itself a controlled act and gets its own decision later.

### Approval request
- One table `approval_request` holds the request and its nullable decision (one request has at most one decision), with child rows for the named lines. It stores the **threshold in force at submission** (EC-08.1). One `PENDING` request per case, by a partial unique index.
- The database repeats the control: `CHECK (checker IS NULL OR lower(checker) <> lower(maker))`, and a trigger that makes a decided request immutable.
- **Approve**, in one transaction: lock the request and the case, check `PENDING`, check checker differs from maker, **claim the lines now** (new claim sources `WRITE_OFF` and `ADJUSTMENT`), resolve the case, record the decision, write the timeline rows and the audit events. If a line was claimed elsewhere meanwhile the whole transaction rolls back with `409 MATCH_LINE_ALREADY_CLAIMED`, the request stays `PENDING`, and the controller can reject it. A pending request holds no lines.
- **Reject** needs a reason: the request is `REJECTED`, the case stays `INVESTIGATING`, the maker may submit again.
- **Maker equals checker** is refused with `403 SEPARATION_OF_DUTIES_VIOLATION`. The refusal rolls back the decision, so the service first writes an `APPROVAL_DENIED` audit event in its **own committed transaction**, then raises the error (AC-08.3).
- While a request is `PENDING`, match, split, resolve and manual escalation on that case are refused with `409 APPROVAL_PENDING`; assignment and comments are allowed (EC-08.2). Phase 6's system escalation must skip cases with a pending approval.
- A decided request cannot be decided again: `409 APPROVAL_ALREADY_DECIDED`.

### Versioned configuration (last stage of Phase 5)
- An append-only `config_version` table: version, payload as canonical JSON, who, reason, when. A `PUT /api/v1/configuration` (ADMIN) adds a version and writes `CONFIG_CHANGED` with the before and after.
- **Scope of the payload:** the approval threshold map only. Matching configuration is already a snapshot on every run; routing and SLA configuration stay code defaults until a later decision. The approval request stores the version it used.

## Alternatives considered

| Alternative | Why rejected |
| --- | --- |
| Roles in a request header | Anyone can claim CONTROLLER, so role tests prove nothing |
| Enforce only maker differs from checker | Leaves the controller-role rule of AC-08.2 untested until Phase 8 |
| Separate `/write-off` and `/adjust` endpoints | The catalogue and master prompt name `/resolve`; one endpoint with a typed body is simpler for clients and for the approval flow |
| A new `PENDING_APPROVAL` case state | FRD-07 keeps five states; a pending request is enough |
| Claim lines at submission | A pending request could hold lines indefinitely and each rejection adds release rows |
| Separation of duties only in the service | One missed code path would defeat it; the `CHECK` makes the database refuse it too |
| Threshold as application properties only | Changes would not be versioned or audited, against the BRD |

## Consequences

- The existing exception API tests need dev users configured; this is a test-fixture change, not a behaviour change.
- Migrations change `ck_case_resolution` and `ck_claim_source` to admit the two new resolutions and claim sources.
- The first real CONTROLLER-only endpoints exist, and the registry is deliberately temporary.
- Spoofing is still possible until Phase 8 (anyone can send `X-Actor: carol`). The registry makes the **rules** testable, not the caller trustworthy.

## Interview explanation

"Maker-checker is a separation-of-duties control, so I enforce it three times: the service refuses it, the database has a check constraint, and the refusal itself is written to the audit chain. Lines are claimed only at approval, so a pending request doesn't lock anything, and if the world changed meanwhile the approval fails cleanly and the controller can reject. Until real logins exist, identity comes from a dev-only user registry and the app refuses to start without it under another profile."