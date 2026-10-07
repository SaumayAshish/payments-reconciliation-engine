# ADR-0005: PostgreSQL as system of record, JDBC access, and a transactional outbox for events

- Status: **Accepted** (approved by Caesar, 2026-10-07)
- Date: 2026-10-07
- Related: FR-13, FRD-13, NFR-01, NFR-03, Q-12

## Context

The workload needs strong transactional guarantees (audit with the change, no partial runs), constraints that enforce business invariants (one active match per candidate, unique file hash), reliable ordering, and bulk loading of around a million rows. Later, exception events must reach Kafka without the database and the broker disagreeing.

## Decision

### PostgreSQL is the single system of record
Inputs, matches, exceptions, approvals, configuration versions, audit and outbox all live in PostgreSQL. Constraints, partial unique indexes and append-only triggers are used so invariants hold even if application code has a bug. Migrations are Flyway, versioned and immutable once merged.

### Access via Spring JDBC (`JdbcClient`) with explicit SQL, not JPA
- Bulk inserts (batching, `COPY` where it helps) and index-aware queries are first-class and visible in review.
- No lazy loading, dirty checking or implicit flush that could change ordering or timing in a deterministic engine.
- Repositories are adapters behind ports defined in `application`, so `domain` and `matching` never see JDBC.
- Optimistic locking is an explicit `version` column and `UPDATE ... WHERE id = ? AND version = ?`.

### Transactional outbox for events (Phase 7)
- A business transaction writes its change **and** an `outbox_event` row (event id, type, version, payload, created sequence) atomically.
- A separate publisher polls unpublished rows in sequence order (`FOR UPDATE SKIP LOCKED`), publishes to Kafka, marks them published. Delivery is **at least once**; consumers deduplicate on `eventId` (AC-13.4).
- Kafka being down does not fail business operations; the backlog is a metric (`outbox_backlog`) (AC-13.3).
- Events are versioned and schema-defined (for example `payments.exception.created.v1`), published with a documented contract, not ad-hoc JSON.
- The outbox table exists from Phase 7, not before: no infrastructure without a demonstrated purpose.

## Alternatives considered

| Alternative | Why rejected |
| --- | --- |
| JPA/Hibernate | Convenient for CRUD, but hides SQL, flush order and batching behaviour that matter at 1M rows and for determinism |
| Dual write (DB then Kafka) | A crash between the two leaves them inconsistent; this is the exact failure the outbox prevents |
| Kafka as the source of truth | Adds operational weight and weakens relational constraints we rely on |
| CDC (Debezium) instead of a polling publisher | Valid at scale, but another moving part to run and explain; polling is enough for the workload |
| A document store or key-value store | Gives up joins, constraints and transactional audit, all of which are core here |
| H2 or SQLite for tests | Behaviour differs from PostgreSQL (locks, partial indexes, triggers). Tests use Testcontainers PostgreSQL |

## Consequences

- Integration tests need Docker (Testcontainers). CI runners provide it.
- Writing SQL by hand costs more code than JPA; accepted for control and performance.
- Outbox adds one table and one background component, with a polling latency measured in milliseconds to seconds.
- At-least-once delivery means every consumer must be idempotent, which we test with duplicate-event cases.

## Interview explanation

"I need audit and state changes to be atomic and invariants enforced by the database, so PostgreSQL is the system of record. I use explicit SQL because a million-row load and a deterministic engine need predictable behaviour. For Kafka I use a transactional outbox: the event is written in the same transaction as the change, then published afterwards, so the database and the broker can't diverge."
