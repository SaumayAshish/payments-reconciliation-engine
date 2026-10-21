# Requirement Traceability Matrix

Maps each BRD requirement to its FRD requirement, planned implementation, planned test and the evidence that will prove it. This is a living document: columns marked **planned** are intentions from Phase 0, not claims. A row only becomes **done** when the test exists, passes in CI, and the evidence is linked.

Status values: `planned` (designed, no code), `in progress`, `done` (test green in CI, evidence linked). Class and test names are proposals and may change during implementation; the matrix is updated in the same commit.

Phase 0 status: **Phase 2 status: FR-01 to FR-03 done; NFR-03 done; NFR-05 and NFR-08 in progress; all others planned.** No production code exists yet.

## Functional requirements

| BRD | FRD | Priority | Implementation (planned) | Tests (planned) | Evidence (planned) | Phase | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| FR-01 | FRD-01 | Must | `IngestionService`, `IngestionStore` and `JdbcIngestionStore`, `IngestionController`, `CsvParser`, `Camt053Parser`, V1 unique constraint | `IngestionApiIT` (ten uploads, eight concurrent, statement vs ledger kinds, refused files store nothing), `ProcessKillRecoveryIT` (AC-01.5), `JdbcIngestionStoreIT`, `SchemaMigrationIT`, `IngestionServiceTest`, `CsvParserTest`, `Camt053ParserTest` | CI (GitHub Actions) | 2 | done |
| FR-02 | FRD-02 | Must | `LineNormaliser`, `AmountParser`, `ReferenceNormaliser`, `CounterpartyNormaliser`, `Money` | `MoneyTest`, `MoneyPropertyTest`, `AmountParserTest`, `NormaliserTest`, `LineNormaliserTest`, `IngestionServiceTest` | CI | 1 to 2 | done |
| FR-03 | FRD-03 | Must | `IngestionService` (CSV and JSON ledger), `LedgerJsonParser`, `LedgerEntryIdResolver`, V2 unique `entry_id` index | `LedgerJsonParserTest`, `LedgerEntryIdResolverTest`, `IngestionApiIT` (canonical JSON identity, CSV vs JSON equivalence, entry id duplicate, conflict, same-file and concurrent reuse) | CI | 2 | done |
| FR-04 | FRD-04 | Must | `ExactMatcher`, `ToleranceMatcher`, `FuzzyMatcher`, `OneToManyMatcher`, `TierPipeline` | `Tier1ExactTest` to `Tier4OneToManyTest`, `HardNegativeCorpusTest` | CI, match-rate-by-tier table in README | 3 (master) | planned |
| FR-05 | FRD-05 | Must | `ConfidenceBands`, `ClaimResolver`, `MatchingConfig` | `ConfidenceBandBoundaryTest`, `TieNeverAutoConfirmsTest`, `NoLiteralThresholdsArchTest` | CI | 3 | planned |
| FR-06 | FRD-06 | Must | `ExceptionFactory`, `RoutingRules`, `SlaPolicy` | `ExceptionCreationIT`, `RoutingRulesTest`, `RerunNoDuplicateExceptionsIT` | CI | 3 to 4 | planned |
| FR-07 | FRD-07 | Must | `ExceptionStateMachine`, `ExceptionCase` | `StateMachineAllPairsTest`, `OptimisticLockConflictIT` | CI | 4 | planned |
| FR-08 | FRD-08 | Must | `MakerCheckerPolicy`, `ApprovalService` | `SeparationOfDutiesTest`, `ThresholdBoundaryTest`, `ApprovalFlowIT` | CI, demo script | 5 | planned |
| FR-09 | FRD-09 | Must | `AuditChain`, `AuditVerifier`, append-only trigger migration | `HashChainTest`, `AuditTamperDetectionIT`, `AuditAppendOnlyIT`, `ConcurrentAppendIT` | CI, README tamper test | 5 | planned |
| FR-10 | FRD-10 | Must | `RunService`, `OutputHasher`, `ReplayService` | `ReplayDeterminismTest`, `ShuffledInputHashTest`, `ThreadCountHashTest` | CI, README | 3 | planned |
| FR-11 | FRD-11 | Should | `AgeingReport`, `SlaReport`, `EscalationJob` | `AgeingBucketTest`, `SlaStatusTest`, `ReportDeterminismTest` | CI | 6 | planned |
| FR-12 | FRD-12 | Should | `ManualMatchService`, `UnmatchService` | `ManualMatchIT`, `SplitSumTest`, `UnmatchCreatesLinkedCaseIT` | CI | 4 | planned |
| FR-13 | FRD-13 | Could | `OutboxWriter`, `OutboxPublisher`, `ExceptionEventConsumer` | `OutboxAtomicityIT`, `KafkaDownIT`, `DuplicateEventIT` | CI | 7 | planned |
| FR-14 | FRD-14 | Could | Thin read-only dashboard | UI smoke test | Screenshot in README | 9 | planned |

## Non-functional requirements

| BRD | Target | Verification (planned) | Evidence location | Phase | Status |
| --- | --- | --- | --- | --- | --- |
| NFR-01 | 1M lines matched in under 5 minutes, laptop-class | JMH benchmark plus end-to-end timed run on the 1M corpus | `docs/benchmarks.md` (hardware, JDK, command, runtime) | 10 | planned |
| NFR-02 | Zero false auto-matches on labelled corpus | `CorpusGroundTruthTest` compares every confirmed pair to ground truth | CI, README table | 3 | planned |
| NFR-03 | 10 re-ingests leave counts unchanged | `IngestionApiIT.tenUploadsOfTheSameStatementCreateExactlyOneIngestion` (real HTTP, real PostgreSQL) | CI | 2 | done |
| NFR-04 | Identical output hashes on replay | Replay test in CI | CI | 3 | planned |
| NFR-05 | No floating point in money paths | `SourcePolicyTest` source scan and `ArchitectureTest` (BigDecimal confined to `ingestion.normalise`); re-verified as each phase adds code | CI | 1 onward | in progress |
| NFR-06 | Tampering detected | `AuditTamperDetectionIT` edits one row, expects failure at that seq | CI, README | 5 | planned |
| NFR-07 | Metrics exported | `MetricsExportIT` reads the registry for each named metric | CI | 6 | planned |
| NFR-08 | 85 percent line coverage on `domain` and `matching`, merge blocked below | JaCoCo check bound to `verify`, run by the CI workflow (`matching` has no code until Phase 3) | CI | 1 onward | in progress |

## Master-prompt additions (not in the BRD)

These come from the master prompt and are tracked separately so BRD traceability stays clean.

| Source | Requirement | Phase | Status |
| --- | --- | --- | --- |
| Master 18 | API-first: OpenAPI per endpoint group before implementation | each phase | planned |
| Master 19 | RBAC and server-side authorization | 8 | planned (Q-13 covers the interim) |
| Master 23 | Structured logs with correlation ids | 6 | planned |
| Master 36 | Consistent error model, no internals leaked | 1 onward | planned |
| Master 42 | Dependency, secret and container scanning | 11 | planned |

## Open questions that block rows

| Question | Blocks |
| --- | --- |
| Q-02 match-rate definition | FR-04, FR-05, NFR-02 gate wording, corpus proportions |
| Q-03, Q-04 tier ceilings and fuzzy date bound | FR-04, FR-05 |
| Q-07 run input scope | FR-10, NFR-04 |
| Q-09, Q-10 thresholds and SLA values | FR-06, FR-08, FR-11 |
| Q-13 interim identity | FR-08, FR-09 |
