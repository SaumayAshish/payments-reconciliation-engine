# Architecture

Status: **Proposal for Phase 0 review.** Diagrams are Mermaid so they render on GitHub and stay in version control. Requirements: [BRD](BRD.md), [FRD](FRD.md). Decisions: [ADRs](adr/).

## 1. Principles

1. **Simple topology, deep quality.** One deployable (a modular monolith) and one database. No microservices, no cache, no search engine. Kafka arrives only in Phase 7 behind an outbox.
2. **Pure core.** `domain` and `matching` are plain Java: no Spring, no JDBC, no JSON annotations. ArchUnit enforces it. This is what makes determinism, property testing and benchmarking cheap.
3. **PostgreSQL is the system of record** for inputs, outputs, exceptions, approvals, audit and outbox ([ADR-0005](adr/0005-postgresql-system-of-record-and-outbox.md)).
4. **Everything that matters is replayable.** Inputs are immutable and sequenced; runs record exactly what they consumed ([ADR-0003](adr/0003-deterministic-matching.md)).
5. **Audit in the same transaction** as the change it describes ([ADR-0004](adr/0004-hash-chained-audit-log.md)).

## 2. System context

```mermaid
flowchart LR
    subgraph Actors
        AN[Analyst]
        TL[Team lead]
        CT[Controller]
        AU[Auditor]
        AD[Platform engineer]
    end
    BANK[(Bank statement files<br/>CSV / camt.053)]
    LEDG[(Ledger feed<br/>REST / CSV)]
    SYS{{Reconciliation<br/>and Exceptions Engine}}
    DS[(Downstream consumers<br/>via Kafka, Phase 7)]
    BANK --> SYS
    LEDG --> SYS
    AN --> SYS
    TL --> SYS
    CT --> SYS
    AU --> SYS
    AD --> SYS
    SYS --> DS
```

## 3. Components

Seven components and one database, matching the BRD.

```mermaid
flowchart LR
    API[API layer<br/>Spring Boot, DTOs, validation, error model]
    ING[Ingestion<br/>parsers, normaliser, idempotency]
    MAT[Matching engine<br/>tiers 1-4, scoring, claim resolution]
    EXC[Exceptions<br/>state machine, routing, maker-checker]
    REP[Reporting<br/>ageing, SLA, match reports]
    AUD[Audit<br/>hash chain, verify]
    EVT[Event feed<br/>outbox publisher, Phase 7]
    DB[(PostgreSQL)]

    API --> ING
    API --> MAT
    API --> EXC
    API --> REP
    API --> AUD
    ING --> DB
    MAT --> DB
    EXC --> DB
    EXC --> AUD
    AUD --> DB
    REP --> DB
    EVT --> DB
    EVT --> K[(Kafka)]
```

Work flows left to right: ingestion fills the canonical tables, matching consumes them and produces matches and exceptions, exceptions are worked by people. Reporting, audit verification and the event feed only read what the others wrote.

### 3.1 Package layout

```text
com.saumay.payments
├── domain                 plain Java, no framework imports
│   ├── money              Money, CurrencyCode, minor-unit arithmetic
│   ├── model              CanonicalLine, StatementLine, LedgerEntry, BusinessDate, ids
│   ├── cases              ExceptionCase, state machine, routing, SLA clock
│   ├── approval           ApprovalRequest, maker-checker policy
│   └── audit              AuditEvent, hash chain, verifier
├── matching               plain Java: tiers, scoring, candidate index, claim resolver, output hash
├── ingestion              plain Java: CSV and camt.053 parsers, normaliser (the only place BigDecimal is allowed)
├── application            use-case services and ports (repositories, Clock, ActorContext)
├── infrastructure
│   ├── persistence        JDBC adapters, Flyway migrations live in resources
│   ├── outbox             outbox writer and publisher (Phase 7)
│   └── metrics            Micrometer adapters
├── api                    Spring Boot controllers, DTOs, error mapping, security (Phase 8)
└── config                 Spring configuration and typed properties
```

ArchUnit rules (written in Phase 1, run on every build):

| Rule | Enforces |
| --- | --- |
| `domain..`, `matching..`, `ingestion..` depend on no `org.springframework..`, `jakarta..`, `java.sql..`, `com.fasterxml..` | Pure core |
| `domain..`, `matching..` and the normaliser output may not use `double`, `float`, `Double`, `Float` | NFR-05 |
| `java.math.BigDecimal` only inside `ingestion.normalise..` | Money enters the system as text and leaves as `long` |
| `domain..` and `matching..` may not call `System.currentTimeMillis`, `Instant.now()`, `LocalDate.now()`, `Random`, `UUID.randomUUID()` | Determinism |
| `api..` may not be referenced from any other package | Layering |
| `infrastructure..` may not be referenced from `domain`, `matching`, `application` | Dependency inversion |

## 4. Key sequences

### 4.1 Idempotent ingestion

```mermaid
sequenceDiagram
    participant C as Client
    participant A as API
    participant S as IngestionService
    participant D as PostgreSQL
    C->>A: POST /ingestions/statements (file)
    A->>S: ingest(bytes, kind)
    S->>S: fileHash = SHA-256(bytes)
    S->>D: BEGIN
    S->>D: pg_advisory_xact_lock(hash64(kind, fileHash))
    S->>D: SELECT ingestion WHERE kind, file_hash
    alt already ingested
        D-->>S: existing ingestion
        S->>D: ROLLBACK
        S-->>A: existing, duplicate=true
    else new
        S->>S: parse and normalise lines
        S->>D: INSERT ingestion_file, lines, rejected_lines (batched)
        S->>D: COMMIT
        S-->>A: new ingestion, duplicate=false
    end
    A-->>C: 200 OK (id, accepted, rejected, duplicate)
```

A crash before `COMMIT` leaves nothing behind, so a retry starts clean. The advisory lock serialises concurrent identical uploads; the unique constraint on `(kind, file_hash)` is the final backstop.

### 4.2 Reconciliation run

```mermaid
sequenceDiagram
    participant C as Client
    participant R as RunService
    participant E as MatchingEngine (pure)
    participant D as PostgreSQL
    C->>R: POST /reconciliations/runs (businessDate)
    R->>D: BEGIN, create run (config version, cutoff seq, batch ids)
    R->>D: load statement lines and candidate ledger entries (ordered by seq)
    R->>E: match(inputs, config)
    E-->>R: matches, review items, breaks, tier stats
    R->>R: outputHash = SHA-256(canonical output)
    R->>D: INSERT matches, exceptions, run summary, audit RUN_COMPLETED(outputHash)
    R->>D: COMMIT
    R-->>C: run id, counts, outputHash
    Note over C,D: Replay re-reads the same inputs by cutoff seq and compares outputHash
```

### 4.3 Maker-checker

```mermaid
sequenceDiagram
    participant M as Maker (analyst)
    participant X as ExceptionService
    participant K as Checker (controller)
    participant D as PostgreSQL
    M->>X: write-off request (case, amount, reason)
    X->>D: BEGIN
    X->>X: amount > threshold(currency)?
    X->>D: INSERT approval_request PENDING (threshold snapshot)
    X->>D: append audit APPROVAL_REQUESTED
    X->>D: COMMIT
    K->>X: approve(requestId)
    X->>X: checker != maker and role CONTROLLER?
    alt violation
        X->>D: append audit APPROVAL_DENIED (SoD)
        X-->>K: 403 SEPARATION_OF_DUTIES_VIOLATION
    else allowed
        X->>D: BEGIN
        X->>D: approval APPROVED, case RESOLVED
        X->>D: append audit APPROVAL_DECIDED and CASE_RESOLVED
        X->>D: COMMIT
    end
```

### 4.4 Audit verification

```mermaid
sequenceDiagram
    participant U as Auditor
    participant V as AuditVerifier
    participant D as PostgreSQL
    U->>V: audit verify
    V->>D: stream events ORDER BY seq
    loop each event n
        V->>V: recompute hash(n) from stored fields and previousHash
        V->>V: compare with stored hash(n) and link to hash(n-1)
    end
    V-->>U: OK(count, headHash) or FAIL(firstBadSeq, reason)
```

## 5. Exception state machine

```mermaid
stateDiagram-v2
    [*] --> OPEN: created by run
    OPEN --> ASSIGNED: assign / routing
    ASSIGNED --> INVESTIGATING: start work
    INVESTIGATING --> RESOLVED: match / split / adjust / write-off (approved if controlled)
    INVESTIGATING --> ESCALATED: escalate
    OPEN --> ESCALATED: SLA breach
    ASSIGNED --> ESCALATED: SLA breach
    ESCALATED --> INVESTIGATING: senior takes over
    ESCALATED --> ASSIGNED: re-assign higher
    RESOLVED --> [*]
```

Eight legal transitions, all others rejected. `RESOLVED` is terminal (Open question Q-08).

## 6. Domain concepts and what gets persisted

Not every concept deserves a table. The rule: persist what must survive a restart, be queried, or be proven to an auditor.

| Concept | Persisted? | Decision and reason |
| --- | --- | --- |
| IngestionFile / IngestionBatch | Yes, one table | Idempotency anchor and provenance. `IngestionBatch` and `IngestionFile` are the same thing in v1: one file is one batch. Merged to avoid a pointless join |
| StatementLine, LedgerEntry | Yes, `statement_line`, `ledger_entry` | Immutable inputs. Separate tables because their lifecycles and indexes differ, same canonical columns |
| RejectedLine | Yes | Data-quality observability (never silently dropped) |
| ReconciliationRun | Yes | First-class, carries cut-off, config version, output hash |
| Match, MatchCandidate | `match` yes; candidates **no** in general | Candidates are persisted only for review and break cases (top 3), as JSON inside the exception case. Persisting all candidates for 1M lines would be waste |
| ExceptionCase | Yes | The working object |
| ExceptionAssignment | Yes, append-only | Original owner must stay on record |
| ExceptionComment | Yes | Activity timeline |
| ExceptionAttachment | Metadata only | v1 stores name, size, hash and a pointer, not file bytes |
| ApprovalRequest / ApprovalDecision | Yes, one table | A request has at most one decision; two tables would only add a join. Decision columns are nullable until decided |
| AuditEvent | Yes, append-only | The evidence |
| MatchingConfiguration (ReconciliationRule, thresholds) | Yes, versioned rows | Each change is a new immutable version, audited |
| SlaPolicy | Yes, inside the configuration version | Versioned with the rest of the business configuration |
| SlaClock, SlaBreach | **No** | Derived: computed from `created_business_date`, priority and the as-of date. Storing it would create a second source of truth that can drift. Breach is recorded as an audit event and a state change to `ESCALATED` |
| User, Role | Phase 8 | Until then an `ActorContext` port supplies an identity (Q-13) |
| OutboxEvent | Yes, Phase 7 | Transactional outbox |

## 7. Database design (proposal)

PostgreSQL, Flyway migrations. Money columns are `bigint` minor units with a `char(3)` currency; no `numeric` and no `float` for money anywhere.

```mermaid
erDiagram
    INGESTION_FILE ||--o{ STATEMENT_LINE : contains
    INGESTION_FILE ||--o{ LEDGER_ENTRY : contains
    INGESTION_FILE ||--o{ REJECTED_LINE : rejects
    RECON_RUN ||--o{ MATCH : produces
    RECON_RUN ||--o{ EXCEPTION_CASE : raises
    MATCHING_CONFIG ||--o{ RECON_RUN : "configVersion"
    STATEMENT_LINE ||--o{ MATCH_STATEMENT_LINE : "in"
    LEDGER_ENTRY ||--o{ MATCH_LEDGER_ENTRY : "in"
    MATCH ||--|{ MATCH_STATEMENT_LINE : has
    MATCH ||--|{ MATCH_LEDGER_ENTRY : has
    EXCEPTION_CASE ||--o{ EXCEPTION_ASSIGNMENT : history
    EXCEPTION_CASE ||--o{ EXCEPTION_COMMENT : timeline
    EXCEPTION_CASE ||--o{ EXCEPTION_ATTACHMENT : metadata
    EXCEPTION_CASE ||--o{ APPROVAL_REQUEST : needs
    AUDIT_EVENT }o--|| EXCEPTION_CASE : "entity ref (no FK)"

    INGESTION_FILE {
        bigint id PK
        text kind "STATEMENT or LEDGER"
        char64 file_hash "UNIQUE with kind"
        int accepted_count
        int rejected_count
        timestamptz ingested_at
    }
    STATEMENT_LINE {
        bigint seq PK
        bigint ingestion_id FK
        text line_key "UNIQUE"
        text account
        bigint amount_minor
        char3 currency
        date value_date
        text reference_norm
        text counterparty_norm
    }
    LEDGER_ENTRY {
        bigint seq PK
        bigint ingestion_id FK
        text line_key "UNIQUE"
        text account
        bigint amount_minor
        char3 currency
        date value_date
        text reference_norm
        text counterparty_norm
    }
    RECON_RUN {
        bigint id PK
        date business_date
        int config_version FK
        bigint ledger_cutoff_seq
        text status
        char64 output_hash
    }
    MATCH {
        bigint id PK
        bigint run_id FK
        text tier
        numeric score "0 to 1, scaled int in code"
        text reason
        text state "CONFIRMED, REVERSED"
    }
    EXCEPTION_CASE {
        bigint id PK
        text kind "REVIEW or BREAK"
        text type
        text state
        text team
        text priority
        date created_business_date
        bigint version "optimistic lock"
    }
    APPROVAL_REQUEST {
        bigint id PK
        bigint case_id FK
        text action "WRITE_OFF or ADJUSTMENT"
        bigint amount_minor
        text maker
        text checker "nullable"
        text decision "nullable"
    }
    AUDIT_EVENT {
        bigint seq PK
        text entity_type
        text entity_id
        text actor
        text action
        jsonb before_state
        jsonb after_state
        bigint occurred_at_micros
        char64 previous_hash
        char64 current_hash
    }
```

Score is shown as `numeric` in the diagram for readability; in code and storage it is a scaled integer (`score_milli`, 0 to 1000) so no floating point reaches a comparison against a threshold.

### 7.1 Key indexes and why

| Index | Purpose |
| --- | --- |
| `UNIQUE (kind, file_hash)` on `ingestion_file` | Idempotency backstop (FRD-01) |
| `UNIQUE (line_key)` on both line tables | Deterministic identity, safe re-insert |
| `(account, currency, amount_minor, value_date)` on `ledger_entry` | Tier 1 and 2 candidate lookup |
| `(account, currency, amount_minor)` on `ledger_entry` | Tier 3 and 4 candidate pools |
| `(seq)` ordered scan with `seq <= cutoff` | Replay cut-off (Q-07) |
| `(state, team, priority)` partial index where `state <> 'RESOLVED'` on `exception_case` | The working queue |
| `(created_business_date)` on `exception_case` | Ageing |
| `UNIQUE (line_key, kind)` on `exception_case` | No duplicate exceptions on re-run (AC-06.3) |
| `(entity_type, entity_id, seq)` on `audit_event` | Per-entity history view |
| `UNIQUE (seq)` and a trigger rejecting `UPDATE` and `DELETE` on `audit_event` | Append-only (AC-09.6) |
| Partial unique index on `match_ledger_entry (ledger_seq)` where match state is `CONFIRMED` | A candidate has at most one active match (AC-05.4), enforced by the database, not only the code |

### 7.2 Transactions and concurrency

- Ingestion: one transaction per file, advisory lock on the hash.
- Run: one transaction per run. A run that fails leaves no partial matches or exceptions.
- Cases: optimistic locking via `version`; conflicts return `CONCURRENT_MODIFICATION`.
- Audit append: serialised with a transaction-scoped advisory lock so the chain stays linear.
- Isolation: `READ COMMITTED` plus the constraints above. Revisit only if a test shows an anomaly.

## 8. Observability

Metrics (Micrometer, Prometheus format when scraped). Labels are low-cardinality only: never account, line, case or user ids.

| Metric | Type | Labels |
| --- | --- | --- |
| `reconciliation_match_rate` | gauge | `run_type` |
| `reconciliation_exception_count` | gauge | `type` |
| `reconciliation_run_duration_seconds` | timer | none |
| `statement_ingestion_duration_seconds` | timer | `kind` |
| `exception_queue_depth` | gauge | `team`, `state` |
| `sla_breach_count` | counter | `team` |
| `matching_tier_distribution` | counter | `tier` |
| `outbox_backlog` (Phase 7) | gauge | none |

Logs are structured JSON with `correlationId`, and where relevant `runId`, `ingestionId`, `exceptionId` in the logging context. Account numbers and free-text narratives are never logged.

## 9. Infrastructure

| Component | Phase | Purpose |
| --- | --- | --- |
| PostgreSQL | 1 | System of record |
| Application container | 1 | The service |
| Prometheus and Grafana | 6 | Only if they display the metrics above in the demo; otherwise skipped |
| Kafka and Kafka UI | 7 | Event feed (FR-13), behind a Compose profile |

## 10. Phase mapping

The BRD has six phases; the master prompt splits the work into twelve. They are the same work at different resolution.

| BRD phase | Master phases | Notes |
| --- | --- | --- |
| 0 Docs first | 0 | This phase |
| 1 Core and ingestion | 1 Domain and database, 2 Ingestion | |
| 2 Matching | 3 Matching engine | Includes corpus and first JMH |
| 3 Exceptions and audit | 4 Exceptions, 5 Audit and maker-checker | |
| 4 Reporting and ops | 6 Reporting and observability | |
| 5 Polish | 11 Production polish | |
| none (Could / extension) | 7 Kafka, 8 Security, 9 UI | Beyond BRD scope; security is needed for real maker-checker (Q-13) |
| none | 10 Performance and reliability | Measured numbers for NFR-01 and the failure demos |

Q-12 asks you to confirm this mapping. Total duration will exceed the BRD's seven weeks.

## 11. Interview explanations

- **Why a modular monolith?** One transactional boundary gives us atomic audit and outbox writes for free; splitting would buy distributed-systems problems with no scaling need.
- **Why plain-Java core?** The hardest requirements (determinism, money safety) are properties of pure code. Keeping frameworks out makes them testable in milliseconds and benchmarkable.
- **Why JDBC with explicit SQL rather than an ORM?** Bulk inserts of a million rows, exact control over ordering and locks, and no hidden flushes. Recorded in ADR-0005.
