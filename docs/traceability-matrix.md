# Requirement Traceability Matrix

Maps each BRD requirement to its FRD requirement, planned implementation, planned test and the evidence that will prove it. This is a living document: columns marked **planned** are intentions from Phase 0, not claims. A row only becomes **done** when the test exists, passes in CI, and the evidence is linked.

Status values: `planned` (designed, no code), `in progress`, `done` (test green in CI, evidence linked). Class and test names are proposals and may change during implementation; the matrix is updated in the same commit.

Phase 4 status: **backend and REST API done (ADR-0007, ADR-0008, ADR-0009); FR-06, FR-07 and FR-12 implemented, except the items that need Phase 5 (audit events for case actions, write-off and adjustment) and Phase 6 (system escalation on SLA breach); awaiting the Phase 4 gate review.**

Recorded deviation (AC-05.2): review-band exceptions carry the proposed match only, not the top 3 candidates. Ranking three candidates would change the engine output and the Phase 3 hashes, so it is deferred and tracked as technical debt.

Phase 3 status: **FR-01 to FR-05 and FR-10 done; NFR-02, NFR-03 and NFR-04 done; NFR-01 in progress (engine measured, database-backed timing in Phase 10); NFR-05 and NFR-08 in progress; all others planned.**

## Functional requirements

| BRD | FRD | Priority | Implementation (planned) | Tests (planned) | Evidence (planned) | Phase | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| FR-01 | FRD-01 | Must | `IngestionService`, `IngestionStore` and `JdbcIngestionStore`, `IngestionController`, `CsvParser`, `Camt053Parser`, V1 unique constraint | `IngestionApiIT` (ten uploads, eight concurrent, statement vs ledger kinds, refused files store nothing), `ProcessKillRecoveryIT` (AC-01.5), `JdbcIngestionStoreIT`, `SchemaMigrationIT`, `IngestionServiceTest`, `CsvParserTest`, `Camt053ParserTest` | CI (GitHub Actions) | 2 | done |
| FR-02 | FRD-02 | Must | `LineNormaliser`, `AmountParser`, `ReferenceNormaliser`, `CounterpartyNormaliser`, `Money` | `MoneyTest`, `MoneyPropertyTest`, `AmountParserTest`, `NormaliserTest`, `LineNormaliserTest`, `IngestionServiceTest` | CI | 1 to 2 | done |
| FR-03 | FRD-03 | Must | `IngestionService` (CSV and JSON ledger), `LedgerJsonParser`, `LedgerEntryIdResolver`, V2 unique `entry_id` index | `LedgerJsonParserTest`, `LedgerEntryIdResolverTest`, `IngestionApiIT` (canonical JSON identity, CSV vs JSON equivalence, entry id duplicate, conflict, same-file and concurrent reuse) | CI | 2 | done |
| FR-04 | FRD-04 | Must | `ExactTier`, `ToleranceTier`, `FuzzyTier`, `OneToManyTier`, `PairResolver`, `MatchingPipeline`, `ExceptionClassifier` | `ExactTierTest`, `ToleranceTierTest`, `FuzzyTierTest`, `OneToManyTierTest`, `PairResolverTest`, `MatchingPipelineTest`, `ExceptionClassifierTest`, `CorpusEvaluationTest` (hard negatives, crowded layout) | CI; `docs/benchmarks.md` (92.4 percent auto-confirmed at 10K, 100K and 1M lines); ADR-0006 | 3 | done |
| FR-05 | FRD-05 | Must | `MatchingConfig`, `MatchScore`, `MatchTier`, `PairResolver` (ties are never auto-confirmed) | `MatchingConfigTest`, `MatchScoreTest`, `MatchTierTest`, `PairResolverTest`, `MatchingDeterminismTest` | CI; audited configuration changes arrive with FR-09 in Phase 5 | 3 | done |
| FR-06 | FRD-06 | Must | `CaseIntakeService`, `RoutingRules`, `RoutingConfig`, `SlaPolicy`, `JdbcCaseIntakeStore`, `CaseIntakeController` (`POST /reconciliations/runs/{id}/exceptions`) | `CaseIntakeServiceTest`, `RoutingRulesTest`, `RoutingConfigTest`, `SlaPolicyTest`, `CaseKindMappingTest`, `CasePriorityAndKindTest`, `JdbcCaseIntakeStoreIT` (one case per natural key), `CaseIntakeApiIT` (repeat creates none, unknown run 404) | CI; `docs/api/openapi.yaml`; ADR-0007, ADR-0008 | 3 to 4 | done |
| FR-07 | FRD-07 | Must | `CaseStatus`, `IllegalCaseTransitionException`, `CaseService`, `JdbcCaseStore` (version-guarded updates), trigger `guard_exception_case` (V4), `ExceptionController`, `JdbcCaseReader` | `CaseStatusTest`, `CaseServiceTest`, `JdbcCaseStoreIT`, `CaseSchemaIT` (trigger refuses illegal moves and a wrong version step), `JdbcCaseReaderIT`, `ExceptionApiIT` (workflow, 409 on stale version and illegal transition), `CaseErrorStatusTest` | CI; `docs/api/openapi.yaml`; ADR-0007. AC-07.1 audit events: Phase 5 (`case_activity` is the interim record). System escalation on SLA breach: Phase 6 | 4 | done, except audit events and system escalation |
| FR-08 | FRD-08 | Must | `MakerCheckerPolicy`, `ApprovalService` | `SeparationOfDutiesTest`, `ThresholdBoundaryTest`, `ApprovalFlowIT` | CI, demo script | 5 | planned |
| FR-09 | FRD-09 | Must | `AuditChain`, `AuditVerifier`, append-only trigger migration | `HashChainTest`, `AuditTamperDetectionIT`, `AuditAppendOnlyIT`, `ConcurrentAppendIT` | CI, README tamper test | 5 | planned |
| FR-10 | FRD-10 | Must | `ReconciliationRun`, `CanonicalOutput`, `ReconciliationRunService`, `JdbcRunStore`, `ReconciliationController` (replay endpoint) | `ReconciliationRunTest`, `CanonicalOutputTest`, `MatchingDeterminismTest`, `ReconciliationRunServiceTest`, `JdbcRunStoreIT`, `ReconciliationApiIT`; AC-10.5 holds by construction but has no multi-thread test | CI; identical output hashes across repeats in `docs/benchmarks.md` | 3 | done |
| FR-11 | FRD-11 | Should | `AgeingReport`, `SlaReport`, `EscalationJob` | `AgeingBucketTest`, `SlaStatusTest`, `ReportDeterminismTest` | CI | 6 | planned |
| FR-12 | FRD-12 | Should | `ManualMatchRules`, `CaseMatchService`, `JdbcCaseMatchStore`, claim ledger `line_claim` (V5 to V7), `ExceptionController` (match and both unmatch endpoints) | `ManualMatchRulesTest`, `CaseMatchServiceTest`, `JdbcCaseMatchStoreIT` (match, split, bad sums, claimed line, two-thread race with exactly one winner, unmatch, double unmatch), `ClaimSchemaIT`, `JdbcRunClaimsIT`, `ExceptionApiIT` | CI; `docs/api/openapi.yaml`; ADR-0009. AC-12.5 and audit: Phase 5 | 4 | done, except AC-12.5 and audit events |
| FR-13 | FRD-13 | Could | `OutboxWriter`, `OutboxPublisher`, `ExceptionEventConsumer` | `OutboxAtomicityIT`, `KafkaDownIT`, `DuplicateEventIT` | CI | 7 | planned |
| FR-14 | FRD-14 | Could | Thin read-only dashboard | UI smoke test | Screenshot in README | 9 | planned |

## Non-functional requirements

| BRD | Target | Verification (planned) | Evidence location | Phase | Status |
| --- | --- | --- | --- | --- | --- |
| NFR-01 | 1M lines matched in under 5 minutes, laptop-class | `BenchRunner` and JMH on the labelled corpus (engine only, in memory); database-backed timing in Phase 10 | `docs/benchmarks.md`: 12.8 s cold for the matching run at 1M lines | 3, 10 | in progress |
| NFR-02 | Zero false auto-matches on labelled corpus | `CorpusEvaluationTest` compares every confirmed pair to ground truth (3,000 lines, six further seeds, crowded layout); `Tier4RecallProbe` | CI, ADR-0006 | 3 | done |
| NFR-03 | 10 re-ingests leave counts unchanged | `IngestionApiIT.tenUploadsOfTheSameStatementCreateExactlyOneIngestion` (real HTTP, real PostgreSQL) | CI | 2 | done |
| NFR-04 | Identical output hashes on replay | `ReconciliationRunTest`, `ReconciliationApiIT` (replay over HTTP), repeated runs in `BenchRunner` | CI, `docs/benchmarks.md` | 3 | done |
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
