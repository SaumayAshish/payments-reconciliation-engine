# Payments Reconciliation & Exceptions Engine

A Java 25 service that ingests bank statements and ledger entries, auto-matches them with a deterministic four-tier engine, and routes everything else to an auditable exceptions queue with maker-checker approval, SLA tracking and a tamper-evident audit trail.

> **Status: Phase 0 (documentation).** No production code exists yet. Nothing below is claimed as working until its row in the status table says so and links evidence.

## Why this exists

Banks lose analyst hours every day matching statement lines to ledger entries, chasing breaks and proving to auditors what was done and by whom. This project automates the routine matches and makes the remainder fast to resolve, traceable and measured against SLAs. It is built docs-first (BRD, then FRD, then code) so the repository shows engineering rigour and business-analysis craft together. All data is synthetic.

## Status

| Phase | Scope | Status | Gate evidence |
| --- | --- | --- | --- |
| 0 | BRD, FRD, ADRs, architecture, glossary, data spec, runbook, RCA template, repo skeleton | **Awaiting your review** | This repository |
| 1 | Domain model, `Money`, Flyway schema | Not started | |
| 2 | CSV and camt.053 ingestion, idempotency | Not started | |
| 3 | Four-tier matching, corpus, deterministic replay | Not started | |
| 4 | Exceptions, manual match, split, unmatch | Not started | |
| 5 | Audit chain, maker-checker | Not started | |
| 6 | Reporting, metrics, structured logs | Not started | |
| 7 | Kafka and transactional outbox | Not started | |
| 8 | Authentication and RBAC | Not started | |
| 9 | Thin dashboard | Not started | |
| 10 | Performance and reliability runs | Not started | |
| 11 | Production polish | Not started | |

Phase numbering follows the build brief; the mapping to the BRD's six phases is in [architecture.md](docs/architecture.md#10-phase-mapping).

## Engineering evidence

None yet. This section will hold only measured numbers (tests, coverage, match rate by tier, false auto-matches, throughput with hardware named) once they exist. See [docs/benchmarks.md](docs/benchmarks.md).

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
| [RCA template](templates/rca-template.md) | Blameless incident analysis |

## Local setup (target experience, Phase 1 onward)

```bash
cp .env.example .env        # set a local-only password
docker compose up -d        # PostgreSQL (more services arrive in later phases)
mvn verify                  # build and test (Maven wrapper is added in Phase 1)
```

Requires JDK 25 and Docker. The Phase 0 skeleton has **not** been built yet; see the note at the top of [pom.xml](pom.xml).

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
