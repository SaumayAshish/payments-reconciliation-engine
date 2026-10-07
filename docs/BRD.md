# Payments Reconciliation & Exceptions Engine: BRD and Build Plan

Oct 5, 2026 · @Caesar

A Java 25 service that ingests bank statements and ledger entries, auto-matches them, routes the rest to an auditable exceptions queue and reports SLA breaches, built docs-first (BRD, then FRD, then code) so the repo shows engineering rigor and BA craft together.

> Repo note: this file is the exported planning document. The embedded architecture diagram is reproduced and extended in [architecture.md](architecture.md). The kickoff prompt that closed the original document is a working instruction, not a requirement, and is not reproduced here.

## Problem and goals

Banks and their service providers lose analyst hours every day matching bank statement lines to ledger entries, chasing breaks, and proving to auditors what was done and by whom. The engine automates the routine 90 percent and makes the remaining exceptions fast to resolve, fully traceable and measured against SLAs.

- Auto-match at least 90 percent of lines on realistic synthetic data, with zero false auto-matches in the test corpus
- Route every unmatched item to an exception with an owner, an ageing clock and a resolution path
- Keep an append-only audit trail that can replay who changed what, and when
- Reproduce any day's reconciliation exactly: same inputs, same matches
- Ship BRD, FRD and RCA templates in the repo as first-class deliverables, not afterthoughts

## Scope

v1 covers one reconciliation type done properly: bank statement lines against internal ledger entries, multi-currency, daily batches.

| In scope for v1 | Out of scope (listed so the boundary is explicit) |
| --- | --- |
| CSV and simplified camt.053 statement ingestion | Real bank connectivity (SWIFT, host-to-host) |
| Ledger entries via REST and CSV | A full general ledger or posting engine |
| Four-tier matching with confidence scores | Machine-learned matching (a later experiment) |
| Exceptions queue, maker-checker approval, ageing SLAs | Real customer data of any kind: synthetic data only |
| Append-only audit log, SLA and ageing reports | Multi-tenant SaaS, billing, SSO |
| Metrics, structured logs, benchmarks | A polished web UI (a thin read-only dashboard is optional) |

## Users and stakeholders

| Role | What they need | How the system serves them |
| --- | --- | --- |
| Reconciliation analyst | A short, prioritised queue of breaks with suggested matches | Exception queue sorted by value and age, top-3 candidate matches per item |
| Operations team lead | Workload balance and SLA visibility | Assignment, ageing buckets, breach report |
| Finance controller | Sign-off on write-offs and adjustments | Maker-checker approval above a value threshold |
| Internal auditor | Proof of who did what, and a reproducible run | Append-only hash-chained audit log, deterministic replay |
| Platform engineer | Safe re-runs and clear failure signals | Idempotent ingestion, metrics, structured logs |

## Functional requirements

Priority uses MoSCoW: Must, Should, Could.

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-01 | Ingest a statement file once: re-uploading the same file creates zero duplicate lines (content-hash idempotency) | Must |
| FR-02 | Normalise every line to a canonical record: amount in minor units, ISO 4217 currency, value date, cleaned reference, counterparty | Must |
| FR-03 | Accept ledger entries through REST and CSV with the same canonical shape | Must |
| FR-04 | Run tiered matching (exact, tolerance, fuzzy, one-to-many) and store a confidence score and a reason for every match | Must |
| FR-05 | Auto-confirm matches at or above the auto threshold; queue those in the review band; raise an exception below it | Must |
| FR-06 | Create an exception for every unmatched line with type, value, age and a suggested owner | Must |
| FR-07 | Support exception states: open, assigned, investigating, resolved, escalated, with only legal transitions allowed | Must |
| FR-08 | Require a second approver (maker-checker) for write-offs and adjustments above a configurable value | Must |
| FR-09 | Record every state change in an append-only, hash-chained audit log | Must |
| FR-10 | Re-run a past business date and produce byte-identical match output (deterministic replay) | Must |
| FR-11 | Report ageing buckets (0-1, 2-5, 6-10, 10+ days) and SLA breaches per team and per exception type | Should |
| FR-12 | Let an analyst manually match, split, unmatch and re-match, all audited | Should |
| FR-13 | Publish exception events to Kafka for downstream notification | Could |
| FR-14 | Read-only dashboard for queue size, ageing and match rate | Could |

## Non-functional requirements

These are targets to measure and publish, not claims. Each one gets a test or benchmark in the repo.

| ID | Quality | Target | How it is verified |
| --- | --- | --- | --- |
| NFR-01 | Throughput | 1,000,000 statement lines matched in under 5 minutes on a laptop-class machine | JMH plus an end-to-end timed run |
| NFR-02 | Correctness | Zero false auto-matches on the labelled synthetic corpus | Test corpus with known ground truth |
| NFR-03 | Idempotency | Re-ingesting any file 10 times leaves row counts unchanged | Integration test with Testcontainers |
| NFR-04 | Determinism | Replay of a business date produces identical output hashes | Replay test in CI |
| NFR-05 | Money safety | No floating point anywhere in money paths; minor units plus currency | ArchUnit rule plus static check |
| NFR-06 | Auditability | Audit chain verifies end to end; any tampering is detected | Tamper test that edits one row |
| NFR-07 | Observability | Match rate, queue depth, ingest duration and breach count exported as metrics | Micrometer plus a metrics reader |
| NFR-08 | Quality gate | Line coverage of 85 percent or more on domain and matching packages; CI blocks merges below it | JaCoCo in GitHub Actions |

## Architecture

Seven components and one database: ingestion, matching, exceptions, reporting, audit verification, event feed and the API layer, all backed by PostgreSQL. See [architecture.md](architecture.md) for the diagram.

Work flows left to right through ingestion, matching and exceptions; each stage reads and writes PostgreSQL, and reporting, audit verification and the optional event feed read from it. Domain and matching code stay plain Java; Spring Boot wraps only the API layer.

## Matching engine

Tiers run in order, cheapest first, and a line leaves the pipeline at the first tier that confirms it. Every match stores the tier, a score between 0 and 1, and a human-readable reason.

| Tier | Rule | Typical score |
| --- | --- | --- |
| 1. Exact | Same normalised reference, amount, currency and value date | 1.00 |
| 2. Tolerance | Same amount and currency, value date within a configurable window (default 3 business days), reference equal after normalisation | 0.90 to 0.99 |
| 3. Fuzzy | Same amount and currency, counterparty and narrative similar (token and edit-distance blend) | 0.70 to 0.95 |
| 4. One-to-many | One statement line equals the sum of several ledger entries or the reverse (batch settlements), bounded search of at most 8 candidates | 0.80 to 0.95 |

Score bands: 0.95 and above confirms automatically; 0.70 to 0.95 goes to the review queue with the top three candidates; below 0.70 becomes an exception. The thresholds live in configuration and every change is audited.

Two design rules protect correctness. A candidate may be claimed by only one match, resolved by highest score then earliest ledger entry so output is deterministic. A tie that cannot be broken is never auto-confirmed; it goes to review.

## Exception lifecycle and audit trail

Every exception moves through one state machine, and only these transitions are legal:

1. Open: created automatically for an unmatched or low-confidence line, with an ageing clock started.
2. Assigned: a team lead or a routing rule gives it an owner.
3. Investigating: the owner is working it; notes and attachments are recorded.
4. Resolved: closed by a manual match, a split, an adjustment or a write-off. Adjustments and write-offs above the configured value need a second approver who is not the maker.
5. Escalated: an item that breaches its SLA or exceeds the owner's authority moves up, and the original owner stays on record.

The audit log is append-only. Each event stores actor, action, before and after state, timestamp and the hash of the previous event, so editing any row breaks the chain and a verify command reports exactly where. This is the artifact an auditor asks for first, and it is the strongest proof in the repo that you understand regulated operations.

## Build plan

Seven weeks, starting the week telemetry reaches its finish gate. No phase starts until the previous gate is met.

| Phase | Weeks | Deliverables | Exit gate |
| --- | --- | --- | --- |
| 0. Docs first | 1 | BRD, FRD, 5 ADRs, glossary, synthetic data spec, RCA and runbook templates | You can explain every FR in one sentence; docs reviewed by you, not skipped |
| 1. Core and ingestion | 2 | Canonical model, money type, CSV and camt.053 parsers, idempotent ingest, Flyway schema | FR-01 to FR-03 pass; NFR-03 and NFR-05 tests green |
| 2. Matching | 1.5 | Tiers 1 to 4, scoring, deterministic claim resolution, labelled corpus generator | NFR-02 and NFR-04 green; match rate of 90 percent or more on corpus |
| 3. Exceptions and audit | 1.5 | State machine, maker-checker, hash-chained audit log, verify command | FR-05 to FR-10 pass; NFR-06 tamper test green |
| 4. Reporting and ops | 1 | Ageing and SLA reports, Micrometer metrics, JMH benchmarks | NFR-01 and NFR-07 measured and recorded |
| 5. Polish | 1 | README with diagram and numbers, demo script, 2-minute walkthrough video | A stranger runs the demo with one command in under 10 minutes |

## Proof to publish in the README

Recruiters skim, so the README carries the evidence. Aim for these seven items, each with a real number from your own runs:

1. One-line pitch and a 2-minute demo video or GIF
2. The architecture diagram from this doc, kept in sync with the code
3. Match rate by tier on the labelled corpus, with the false auto-match count (target zero)
4. Throughput from the JMH and end-to-end runs, with the hardware named
5. A failure story: kill the process mid-ingest and show that re-running leaves no duplicates
6. The tamper test: edit one audit row and show the verify command catching it
7. Links to the BRD, FRD, ADRs and an anonymised RCA example in `docs/`

Numbers you did not measure do not go in. A smaller honest claim beats a larger one an interviewer can puncture.
