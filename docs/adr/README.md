# Architecture Decision Records

Each ADR records one decision: context, decision, alternatives rejected, consequences, and the interview explanation. Status values: `Proposed`, `Accepted`, `Superseded`. All five Phase 0 ADRs are **Proposed** until you approve them at the Phase 0 gate; an approved ADR is changed only by a new ADR or a dated amendment.

| ADR | Title | Status | Covers |
| --- | --- | --- | --- |
| [0001](0001-money-representation.md) | Money is signed minor units plus an ISO 4217 currency | Proposed | FRD-02, NFR-05 |
| [0002](0002-idempotent-ingestion-by-content-hash.md) | Idempotent ingestion by content hash | Proposed | FR-01, NFR-03 |
| [0003](0003-deterministic-matching.md) | Deterministic matching and claim resolution | Proposed | FR-04, FR-05, FR-10, NFR-02, NFR-04 |
| [0004](0004-hash-chained-audit-log.md) | Append-only, hash-chained audit log | Proposed | FR-09, NFR-06 |
| [0005](0005-postgresql-system-of-record-and-outbox.md) | PostgreSQL system of record, JDBC access, transactional outbox | Proposed | FR-13, NFR-01 |

Planned (written when the decision is actually made, not before): exception state machine and reopen policy (Q-08), configuration versioning, interim actor identity (Q-13), authentication approach (Phase 8), event schema format (Phase 7).

New ADRs use the next number and this shape: Status, Date, Related, Context, Decision, Alternatives considered, Consequences, Interview explanation.
