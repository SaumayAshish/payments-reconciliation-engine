# Payments Reconciliation & Exceptions Engine

A Java 25 service that ingests bank statements and ledger entries, auto-matches them with a deterministic four-tier engine, and routes everything else to an auditable exceptions queue with maker-checker approval, SLA tracking and a tamper-evident audit trail.

> **Status: Phases 0-7 complete; Phase 8 is in progress.** Reporting uses a
> current case snapshot and an explicit as-of date.

## Why this exists

Banks lose analyst hours every day matching statement lines to ledger entries, chasing breaks and proving to auditors what was done and by whom. This project automates the routine matches and makes the remainder fast to resolve, traceable and measured against SLAs. It is built docs-first (BRD, then FRD, then code) so the repository shows engineering rigour and business-analysis craft together. All data is synthetic.

## Status

| Phase | Scope | Status | Gate evidence |
| --- | --- | --- | --- |
| 0 | BRD, FRD, ADRs, architecture, glossary, data spec, runbook, RCA template, repo skeleton | **Complete, approved 2026-10-07** | This repository |
| 1 | Domain model, `Money`, Flyway schema | Complete and approved | Phase 1 gate |
| 2 | CSV and camt.053 ingestion, idempotency | Complete and approved | Phase 2 gate |
| 3 | Four-tier matching, corpus, deterministic replay | Complete and approved | Phase 3 gate |
| 4 | Exceptions, manual match, split, unmatch | Complete and approved | Phase 4 gate |
| 5 | Audit chain, maker-checker | Complete and approved | CI for commit `10fd859` |
| 6 | Reporting, metrics, structured logs | Complete and approved | CI for commit `f0060b8` |
| 7 | Kafka and transactional outbox | Complete | CI for commit `c6d9186` |
| 8 | Authentication and RBAC | In progress | OIDC HTTP verification in progress; compatibility mode remains unsecured |
| 9 | Thin dashboard | Not started | |
| 10 | Performance and reliability runs | Not started | |
| 11 | Production polish | Not started | |

Phase numbering follows the build brief; the mapping to the BRD's six phases is in [architecture.md](docs/architecture.md#10-phase-mapping).

## Engineering evidence

The full local verification for commit `10fd859` passed 483 unit tests and
201 integration tests, with all configured coverage checks met. GitHub Actions
also passed for that commit. The Phase 6 local `mvn verify` passed 496 unit
tests and 210 integration tests, with all configured coverage checks met.
GitHub Actions also passed for Phase 6 commit `f0060b8`, and its gate was
approved. Phase 7 CI passed for commit `c6d9186`. Phase 8 is in progress.
See [docs/benchmarks.md](docs/benchmarks.md)
for separately recorded matching measurements.

## Documentation

| Document | Purpose |
| --- | --- |
| [BRD](docs/BRD.md) | Business requirements, scope, NFRs, build plan |
| [FRD](docs/FRD.md) | Acceptance criteria, edge cases, data dictionary, open questions |
| [Architecture](docs/architecture.md) | Components, sequences, state machine, ERD, indexes, package layout |
| [ADRs](docs/adr/README.md) | Five decisions with alternatives and interview explanations |
| [Glossary](docs/glossary.md) | Domain terms |
| [Synthetic data spec](docs/synthetic-data-spec.md) | How the labelled corpus and hard negatives are generated |
| [Traceability matrix](docs/traceability-matrix.md) | BRD to FRD to code to test to evidence |
| [API catalogue](docs/api/README.md) | Endpoints, roles, idempotency (OpenAPI follows per phase) |
| [Runbook](docs/runbook.md) | Stuck-ingestion procedure and stubs |
| [Audit verification](docs/audit-verification.md) | Audit verification and external anchors |
| [Approval configuration](docs/phase5-configuration.md) | Versioned approval thresholds |
| [Phase 6 reporting](docs/phase6-reporting.md) | Current-snapshot reporting and SLA job |
| [Phase 7 events](docs/phase7-events.md) | Transactional outbox and event delivery |
| [RCA template](templates/rca-template.md) | Blameless incident analysis |

## Local setup (target experience, Phase 1 onward)

```bash
cp .env.example .env        # set a local-only password
docker compose up -d        # PostgreSQL (more services arrive in later phases)
mvn verify                  # build and test (Maven wrapper is added in Phase 1)
```

Requires JDK 25 and Docker Desktop for the integration tests. Configure a
local-only database password before starting the application.

## Repository layout

```text
docs/            requirements, architecture, ADRs, specs, runbook, RCA
templates/       RCA template
src/main/java/   com.saumay.payments.{domain,matching,ingestion,application,infrastructure,api,config}
src/test/java/   tests (unit, integration, architecture) from Phase 1
.github/         CI workflow
docker-compose.yml, .env.example, pom.xml
```

## Rules this project holds itself to

- Money is signed minor units plus an ISO 4217 currency. No floating point in money paths.
- `domain`, `matching` and `ingestion` are plain Java. Architecture tests enforce it.
- No number appears in this README unless it was measured here.
- Synthetic data only.

## Phase 7 event delivery

Reconciliation completion and new exception cases write events to a
transactional PostgreSQL outbox. An opt-in Kafka publisher delivers them at
least once; the run-completed consumer uses durable deduplication and opens
cases in the same database transaction as its processed-event marker.

Event delivery is disabled by default. See [Phase 7 events](docs/phase7-events.md)
for configuration, retry behavior, operational limits and test coverage.
The existing manual case-intake endpoint remains available.


### Phase 8 approval identity checkpoint

OIDC approval submissions carry verified issuer provenance. Approval
decisions require compatible maker and checker provenance; historical legacy
identities are not automatically mapped to OIDC subjects. A stable
one-person/one-subject identity-provider contract is required. The real-HTTP
approval workflow and remaining Phase 8 security work are still outstanding.
See `docs/phase8-security.md`.

### OIDC approval HTTP test

The OIDC integration suite includes a signed-token maker-checker workflow.
Run the suite and the full Maven verification gate to validate the current
checkout. Phase 8 remains in progress; see `docs/phase8-security.md`.

### Explicit security route policies

The experimental OIDC profile specifies the current audit, report and
Actuator route shapes. New routes need an explicit authorization rule and
tests. See `docs/phase8-security.md` for the current security limitations.

### Development compatibility safeguard

The legacy `dev` HTTP profile is unsecured and requires an explicit
`payments.security.allow-insecure-dev=true` opt-in. The setting exists in
test resources for legacy integration tests; it must not be used in
production. Phase 8 still includes migrating those tests and removing the
compatibility path.

### Authenticated recovery testing

The process-kill recovery test uses the OIDC profile with a local test-only
JWK endpoint and signed token. Other legacy functional tests still require
the unsecured `dev` compatibility profile. See `docs/phase8-security.md`.

### Authenticated ingestion testing

The ingestion API integration suite uses the OIDC profile with a local
test-only signing key and JWK endpoint. Other legacy API suites still need
migration. See `docs/phase8-security.md`.

### Authenticated reconciliation and intake testing

The reconciliation and case-intake HTTP integration suites use signed
test tokens with the OIDC profile. The remaining legacy API suites still
need migration; see `docs/phase8-security.md`.

### Authenticated exception testing

The exception API integration suite uses signed OIDC test tokens for its
case workflows. Controlled-resolution legacy tests still need migration;
see `docs/phase8-security.md`.

### Authenticated controlled-resolution testing

The controlled-resolution HTTP integration suite uses signed OIDC test
tokens for maker-checker, audit and role scenarios. See
`docs/phase8-security.md` for the remaining Phase 8 work.