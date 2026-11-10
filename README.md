# Payments Reconciliation & Exceptions Engine

A Java 25 service that ingests bank statements and ledger entries, auto-matches them with a deterministic four-tier engine, and routes everything else to an auditable exceptions queue with maker-checker approval, SLA tracking and a tamper-evident audit trail.

> **Status: Phases 0-5 approved; Phase 6 is in progress.** The ageing report
> uses a current case snapshot and an explicit as-of date.

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
| 6 | Reporting, metrics, structured logs | Gate review in progress | Full local `mvn verify` passed; GitHub CI pending |
| 7 | Kafka and transactional outbox | Not started | |
| 8 | Authentication and RBAC | Not started | |
| 9 | Thin dashboard | Not started | |
| 10 | Performance and reliability runs | Not started | |
| 11 | Production polish | Not started | |

Phase numbering follows the build brief; the mapping to the BRD's six phases is in [architecture.md](docs/architecture.md#10-phase-mapping).

## Engineering evidence

The full local verification for commit `10fd859` passed 483 unit tests and
201 integration tests, with all configured coverage checks met. GitHub Actions
also passed for that commit. The Phase 6 local `mvn verify` passed 496 unit
tests and 210 integration tests, with all configured coverage checks met.
GitHub CI and the Phase 6 gate review are pending. See
[docs/benchmarks.md](docs/benchmarks.md) for separately recorded matching
measurements.

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
