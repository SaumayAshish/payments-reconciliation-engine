# Functional Requirements Document (FRD)

Status: **Approved 2026-10-07 (Phase 0 gate)** · Source: [BRD.md](BRD.md) · Companions: [architecture.md](architecture.md), [glossary.md](glossary.md), [synthetic-data-spec.md](synthetic-data-spec.md), [ADRs](adr/)

This document expands each BRD functional requirement (FR-xx) into a numbered functional requirement (FRD-xx) with Given/When/Then acceptance criteria (AC) and edge cases (EC). Every AC is written so it can become one automated test; IDs are stable so the [traceability matrix](traceability-matrix.md) can point at them.

## 1. Conventions

- **Money**: signed `long` minor units plus ISO 4217 currency. Never `float` or `double` ([ADR-0001](adr/0001-money-representation.md)).
- **Dates**: `value date` and `booking date` are calendar dates without time zone. System timestamps are UTC instants with microsecond precision.
- **Business days**: Monday to Friday. Holiday calendars are out of scope for v1, behind a `BusinessCalendar` interface so they can be added later.
- **Determinism**: no wall-clock reads, no random IDs, no unordered iteration in any calculation that feeds match output ([ADR-0003](adr/0003-deterministic-matching.md)). Time enters only through an injected `Clock`.
- **Score bands** (configurable, audited): `>= 0.95` auto-confirm; `>= 0.70 and < 0.95` review; `< 0.70` exception.
- Requirement keywords MUST / SHOULD / MAY follow RFC 2119 and mirror BRD MoSCoW priorities.

## 2. Assumptions and open questions

Assumptions are things this FRD takes as true. Open questions are places where the BRD is silent or ambiguous. Per the working rules, none are decided silently: each has a recommendation, and **all recommendations below were approved by Caesar on 2026-10-07 ("go with the recommendations")**. Changing one later requires an ADR amendment.

### 2.1 Assumptions

| ID | Assumption |
| --- | --- |
| A-01 | One statement line is reconciled against ledger entries of the **same internal account**. An account is a synthetic identifier such as `NOSTRO-GBP-001`. |
| A-02 | Review-band items and fully unmatched items are both **ExceptionCases** (kind `REVIEW` or `BREAK`) so one queue, one lifecycle and one SLA model serve both. |
| A-03 | All data is synthetic. No real account numbers, names or bank identifiers appear anywhere. |
| A-04 | Input files are UTF-8. Anything else is rejected with a clear error, never guessed. |
| A-05 | A reconciliation run is initiated explicitly (API or command). There is no scheduler in v1. |

### 2.2 Open questions (recommendation applied provisionally)

| ID | Question | Recommendation (applied) | Affects |
| --- | --- | --- | --- |
| Q-01 | **Sign convention.** A bank credit is a ledger debit on a nostro mirror. Which side flips? | Canonical amounts are from the **account owner's perspective**: money in is positive, money out is negative, for both sources. Ledger feeds are expected pre-mirrored to this convention; the loader rejects nothing on sign but a documented mismatch becomes `AMOUNT_MISMATCH`. | FRD-02, FRD-03, FRD-04 |
| Q-02 | **What does "auto-match at least 90 percent" mean?** Matched in any band, or auto-confirmed only? | Match rate = **auto-confirmed lines / total statement lines** on the labelled corpus. The placeholder corpus profile has about 92 percent auto-confirmable lines (see synthetic-data-spec.md section 4); the measured rate is reported, never assumed. Review and exception counts are reported alongside. | NFR-02, Phase 2 gate |
| Q-03 | **Tier 3 and 4 score ceilings.** The BRD gives 0.95 as the top of both ranges, which equals the auto threshold. Can fuzzy or one-to-many ever auto-confirm? | **No by default.** Treat "to 0.95" as an exclusive upper bound (max `0.949`). Fuzzy and one-to-many therefore always land in review unless an operator lowers the auto threshold (audited). This protects the zero-false-auto-match target. | FRD-04, FRD-05 |
| Q-04 | **Fuzzy date constraint.** BRD Tier 3 requires only amount and currency to be equal. Without a date bound, look-alike items months apart would match. | Require **value date within the tolerance window** for Tier 3 as well. | FRD-04 |
| Q-05 | **Which side generates exceptions?** FR-06 says "every unmatched line". | Both sides: unmatched statement lines become `MISSING_LEDGER`; ledger entries still unmatched after the lookback window become `MISSING_STATEMENT`. | FRD-06 |
| Q-06 | **Ageing bucket overlap.** BRD buckets `6-10` and `10+` overlap at 10. | Use `0-1`, `2-5`, `6-10`, `11+` (label shown as `>10`). Age counts **business days** since creation, measured against an explicit as-of business date. | FRD-11 |
| Q-07 | **Run input scope.** For deterministic replay, exactly which rows feed a run? | A run is defined by `(businessDate, configVersion, statementBatchIds, ledgerCutoffSeq)`. Candidate ledger entries are those with `ledger_seq <= ledgerCutoffSeq`, in the same account, not already confirmed-matched by an earlier run, with value date inside the lookback window (default 10 business days). Confirmed matches are immutable; an unmatch writes a reversal row with its own sequence number. | FRD-10, ADR-0003 |
| Q-08 | **Reopen.** The BRD lists five states and no reopen. The master prompt permits a documented reopen. | **No reopen in v1.** `RESOLVED` is terminal. Unmatching a resolved item creates a **new** linked exception, preserving history. | FRD-07, FRD-12 |
| Q-09 | **Maker-checker threshold currency.** FX conversion is out of scope. | Threshold is a **per-currency map in minor units** with a default for unlisted currencies. "Above" means strictly greater than, on the absolute amount. | FRD-08 |
| Q-10 | **SLA targets** are not given in the BRD. | Propose by priority, in business days: P1 = 1, P2 = 2, P3 = 5, P4 = 10. "At risk" = 75 percent of the SLA consumed. Configurable. | FRD-06, FRD-11 |
| Q-11 | **Duplicate upload response.** The BRD example error model suggests an error, but a retry should be safe. | `200 OK` returning the **original ingestion** with `duplicate: true`. No new rows. Reserve the error code for same-hash-different-kind conflicts. | FRD-01 |
| Q-12 | **Phase plan.** The BRD has six phases over seven weeks. The master prompt has twelve phases including Kafka, security and UI. | Treat the master phases as a finer split of the BRD phases (mapping in [architecture.md](architecture.md#phase-mapping)). Expect the schedule to exceed seven weeks. | Plan |
| Q-13 | **Actor identity before Phase 8.** Maker-checker (Phase 5) needs distinct users before Spring Security arrives. | Introduce an `ActorContext` port in Phase 5 with a development implementation (header-based identity, never enabled outside the `dev` profile), replaced by real authentication in Phase 8. | FRD-08, FRD-09 |

## 3. Actors and use cases

| Actor | Role code | Use cases |
| --- | --- | --- |
| Reconciliation analyst | `ANALYST` | UC-03 work an exception, UC-04 manual match / split / unmatch |
| Operations team lead | `TEAM_LEAD` | UC-05 assign and escalate, UC-07 review SLA and ageing |
| Finance controller | `CONTROLLER` | UC-06 approve or reject write-off and adjustment |
| Internal auditor | `AUDITOR` | UC-08 read audit trail, verify chain, replay a run |
| Platform engineer | `ADMIN` | UC-01 ingest, UC-02 run reconciliation, UC-09 change configuration |

| UC | Name | Primary FRD |
| --- | --- | --- |
| UC-01 | Ingest a statement or ledger file | FRD-01, 02, 03 |
| UC-02 | Run reconciliation for a business date | FRD-04, 05, 10 |
| UC-03 | Work an exception | FRD-06, 07 |
| UC-04 | Manually match, split, unmatch | FRD-12 |
| UC-05 | Assign and escalate | FRD-07 |
| UC-06 | Approve a controlled action | FRD-08 |
| UC-07 | Review ageing and SLA | FRD-11 |
| UC-08 | Audit and replay | FRD-09, 10 |
| UC-09 | Change matching configuration | FRD-05, 09 |

## 4. Data dictionary (canonical records)

### 4.1 CanonicalLine (used for both statement lines and ledger entries)

| Field | Type | Rules |
| --- | --- | --- |
| `source` | enum `STATEMENT`, `LEDGER` | Set by ingestion path |
| `account` | string, 1 to 34, `[A-Z0-9-]` | Synthetic identifier only |
| `amountMinor` | long, non-zero | Signed, see Q-01. Zero is rejected |
| `currency` | ISO 4217 alpha-3 | Must be a known currency. Amount fractional digits must not exceed the currency exponent. No rounding, ever |
| `valueDate` | date | Required |
| `bookingDate` | date | Optional, defaults to none (never silently copied from value date) |
| `reference` | string, original | As received, kept for display |
| `referenceNorm` | string | Normalisation below |
| `counterparty` | string, original | As received |
| `counterpartyNorm` | string | Normalisation below |
| `narrative` | string, original | Free text, up to 280 chars |
| `lineKey` | string | Deterministic: `SHA-256(fileHash + ":" + lineNumber)`, hex. Never random |
| `seq` | bigint | Assigned at insert, monotonic, used for cut-offs and tie-breaks |

**Reference normalisation**: Unicode NFKC, upper-case with `Locale.ROOT`, remove every character outside `A-Z0-9`. Leading zeros are preserved because they can be significant.

**Counterparty normalisation**: as above but keep single spaces between tokens, drop punctuation, remove legal-form tokens (`LTD`, `LIMITED`, `PLC`, `INC`, `LLC`, `LLP`, `GMBH`, `AG`, `SA`, `PVT`, `PRIVATE`), collapse whitespace.

### 4.2 Statement CSV columns

`account, booking_date, value_date, amount, currency, reference, counterparty, narrative` with a header row. Amount is a plain decimal string (`-1250.50`), never scientific notation, no thousands separators. Dates are `YYYY-MM-DD`.

### 4.3 Ledger CSV columns

Same eight columns plus an optional `entry_id` (source-system identifier). Ledger REST accepts the same fields as JSON.

### 4.4 camt.053 (simplified)

Parsed with a hardened StAX reader (no DTDs, no external entities). Supported: `Stmt/Acct/Id`, `Ntry/Amt@Ccy`, `Ntry/CdtDbtInd`, `Ntry/BookgDt`, `Ntry/ValDt`, `NtryDtls/TxDtls/Refs/EndToEndId`, `RltdPties/Cdtr|Dbtr/Nm`, `RmtInf/Ustrd`. Unsupported elements are ignored and counted in the ingestion report.

### 4.5 Rejected line

`ingestion_id, line_number, error_code, error_message, raw_excerpt`. Rejected lines are persisted and queryable, never dropped.

### 4.6 Ingestion error codes

**File-level** (the whole file is refused, no ingestion row is created). API code is `INGESTION_<CODE>`.

| Code | HTTP | Meaning |
| --- | --- | --- |
| `EMPTY_FILE` | 422 | Zero bytes, or no records |
| `NO_DATA_ROWS` | 422 | Header or wrapper present but no entries |
| `UNSUPPORTED_ENCODING` | 422 | Not valid UTF-8 (A-04) |
| `MALFORMED_CSV` | 422 | A quoted field is never closed |
| `INVALID_HEADER` | 422 | Missing, duplicate or unsupported column |
| `MALFORMED_XML` | 422 | Not well-formed XML, or contains a DTD or entity declaration |
| `MALFORMED_JSON` | 422 | Not a JSON array of flat objects with string or null values |
| `FILE_TOO_LARGE` | 413 | Over the configured limit (default 10 MB) |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Content-Type not accepted by that endpoint |

**Line-level** (the line is stored as a rejected line; the rest of the file is ingested).

| Code | Meaning |
| --- | --- |
| `INVALID_ACCOUNT`, `INVALID_CURRENCY`, `INVALID_DATE` | Field fails its format rule |
| `INVALID_AMOUNT`, `AMOUNT_PRECISION`, `AMOUNT_RANGE`, `AMOUNT_ZERO` | Amount not a plain decimal, too many decimals for the currency, outside `long`, or zero |
| `MISSING_REFERENCE`, `NARRATIVE_TOO_LONG` | Reference empty after normalisation, narrative over 280 characters |
| `MALFORMED_ROW` | CSV row with the wrong column count or invalid quoting |
| `INVALID_ENTRY_ID` | Ledger `entry_id` not 1 to 64 characters of letters, digits and `. _ : / -` |
| `DUPLICATE_LEDGER_ENTRY` | `entry_id` already stored with identical content |
| `LEDGER_ENTRY_CONFLICT` | `entry_id` already stored with different content (AC-03.4) |

**Phase 2 design decisions (for confirmation).**
- D-01 Files are uploaded as the raw request body with `Content-Type` selecting the format, not as multipart, so the hash covers exactly the bytes sent and oversized bodies are never buffered.
- D-02 AC-03.4 is extended: an `entry_id` reused with identical content is also rejected (`DUPLICATE_LEDGER_ENTRY`), so a corrected re-upload cannot create a second copy of an entry. Within one file the first occurrence wins.
- D-03 Ledger JSON accepts only string or null values; a JSON number for `amount` is refused (ADR-0001: money enters as text).
- D-04 The camt.053 end-to-end id `NOTPROVIDED` is treated as a missing reference.

## 5. Functional requirements

### FRD-01 Idempotent statement ingestion (FR-01, Must)

Ingest a file once. Identity is the SHA-256 of the raw bytes plus the file kind.

| AC | Given / When / Then |
| --- | --- |
| AC-01.1 | **Given** a valid statement file never seen before, **when** it is uploaded, **then** one ingestion is created, every valid line is stored once, and the response returns the ingestion id with counts of accepted and rejected lines. |
| AC-01.2 | **Given** a file already ingested, **when** the same bytes are uploaded again, **then** no rows are inserted, the original ingestion is returned with `duplicate: true`, and the total row counts are unchanged. |
| AC-01.3 | **Given** the same file uploaded ten times, **when** counts are compared, **then** they equal the counts after the first upload (NFR-03). |
| AC-01.4 | **Given** two concurrent uploads of identical bytes, **when** both are processed, **then** exactly one ingestion exists and the other request returns it. |
| AC-01.5 | **Given** the process is killed mid-ingest, **when** it restarts and the file is uploaded again, **then** no partial rows exist from the failed attempt and the new attempt succeeds fully. |
| AC-01.6 | **Given** the identical bytes uploaded as a ledger file after being ingested as a statement, **then** they are treated as different kinds and both ingest (the kind is part of the identity). |

Edge cases:
- **EC-01.1 Partial or truncated file**: a final line with fewer columns is rejected as a line error; the file is still ingested if at least one line is valid and the reject ratio is at or below the configured limit (default 100 percent, i.e. accept-and-report). A file with zero valid lines is rejected as a whole and creates no ingestion.
- **EC-01.2 Same file, different line endings or BOM**: different bytes, therefore a different hash. This is deliberate and documented. Byte identity is the contract.
- **EC-01.3 Identical lines inside one file** (two genuine identical payments): both are kept, because identity is `(file, line number)`. Both are flagged `DUPLICATE_SUSPECT` in the ingestion report.
- **EC-01.4 Overlapping re-exports** (same transactions in a different file): not detected at ingest. The matching engine surfaces them as duplicates (see FRD-04 EC-04.5). Documented as a known limitation.
- **EC-01.5 Empty file, header only, unsupported extension, over size limit**: rejected with a typed error and no ingestion row.

### FRD-02 Canonical normalisation (FR-02, Must)

| AC | Given / When / Then |
| --- | --- |
| AC-02.1 | **Given** amount `1250.50` and currency `GBP`, **then** `amountMinor = 125050`. |
| AC-02.2 | **Given** amount `1500` and currency `JPY` (exponent 0), **then** `amountMinor = 1500`; **given** `1.234` and `KWD` (exponent 3), **then** `1234`. |
| AC-02.3 | **Given** amount `10.005` and currency `GBP`, **then** the line is rejected (`AMOUNT_PRECISION`), not rounded. |
| AC-02.4 | **Given** reference ` inv-2026/0042 `, **then** `referenceNorm = INV20260042`. |
| AC-02.5 | **Given** counterparty `Acme Trading Ltd.`, **then** `counterpartyNorm = ACME TRADING`. |
| AC-02.6 | **Given** the same input processed twice, **then** the canonical output is byte-identical (pure function). |
| AC-02.7 | **Given** a missing reference, invalid currency, invalid date, non-numeric or zero amount, or malformed row, **then** the line is rejected with a specific error code and stored as a rejected line. |

Edge cases:
- **EC-02.1 Negative amounts and reversals**: negative is valid (money out). A reversal is an ordinary line with opposite sign; it is matched on its own merits, never netted against the original automatically.
- **EC-02.2 Currency mismatch** inside a file header versus line: the line currency wins; a mismatch with the account's declared currency is a warning in the ingestion report.
- **EC-02.3 Weekend value date**: accepted, flagged `WEEKEND_VALUE_DATE` as a warning. Business-day distance rolls weekend dates forward to Monday first.
- **EC-02.4 Amount overflow** beyond `long` range: rejected `AMOUNT_RANGE`.
- **EC-02.5 Unicode look-alikes and zero-width characters** in references: removed by NFKC plus the `A-Z0-9` filter.

### FRD-03 Ledger ingestion (FR-03, Must)

| AC | Given / When / Then |
| --- | --- |
| AC-03.1 | **Given** a ledger CSV, **when** it is uploaded, **then** the same canonical record and rules as FRD-02 apply and the same idempotency as FRD-01. |
| AC-03.2 | **Given** a REST `POST /api/v1/ingestions/ledger` with a JSON array, **when** the canonical JSON bytes are identical to a previous call, **then** it is a duplicate (hash over the canonical serialisation, fields in fixed order). |
| AC-03.3 | **Given** the same entries supplied via CSV and via REST, **then** both produce identical canonical records. |
| AC-03.4 | **Given** a ledger entry with a reused `entry_id` and different content, **then** it is rejected `LEDGER_ENTRY_CONFLICT`. |

Edge cases: **EC-03.1** very large JSON body (limit, error); **EC-03.2** ledger entry dated in the far future (warning); **EC-03.3** ledger entries arriving after a run has already used a later cutoff (they get a higher `seq`, so past runs are unaffected).

### FRD-04 Tiered matching (FR-04, Must)

A line leaves the pipeline at the first tier that confirms it. Every match stores `tier`, `score` and a human-readable `reason`.

| Tier | Eligibility (all must hold) | Score |
| --- | --- | --- |
| 1 Exact | same account, `referenceNorm`, `amountMinor`, currency, `valueDate` | `1.00` |
| 2 Tolerance | same account, amount, currency, `referenceNorm`; business-day distance `d` between value dates with `1 <= d <= W` (default `W = 3`) | `0.99 - 0.09 * d / W` (so `W=3`: 0.96, 0.93, 0.90) |
| 3 Fuzzy | same account, amount, currency; value date within `W`; similarity `sim >= 0.60` | `0.70 + (sim - 0.60) / 0.40 * 0.249`, so 0.70 to 0.949 (Q-03, Q-04) |
| 4 One-to-many | subset of up to 8 candidate entries sums exactly to the other side's amount; same account, currency and sign; value dates within `W` | `0.80 + 0.07*batchRef + 0.03*sameDate`, capped at `0.949` |

`sim = 0.6 * tokenSetSimilarity(counterpartyNorm) + 0.4 * (1 - normalisedLevenshtein(narrative_a, narrative_b))`, where `tokenSetSimilarity` is Jaccard over normalised tokens. Weights and the `0.60` floor are configuration. The formulas above are **proposed and will be tuned against the corpus in Phase 2**; any change is recorded as an ADR amendment before the gate.

| AC | Given / When / Then |
| --- | --- |
| AC-04.1 | **Given** a statement line and a ledger entry equal on reference, amount, currency, value date, **then** Tier 1 matches with score 1.00 and reason `EXACT: reference, amount, currency, value date equal`. |
| AC-04.2 | **Given** equal reference, amount, currency and value dates two business days apart, **then** Tier 2 matches with score 0.93 and the reason names the distance. |
| AC-04.3 | **Given** equal amount and currency, reference differs, counterparty tokens overlap strongly, dates within W, **then** Tier 3 matches with a score in `[0.70, 0.949]`. |
| AC-04.4 | **Given** a statement line of 1,000.00 and ledger entries of 600.00, 300.00 and 100.00 in the window, **then** Tier 4 proposes the three-entry match; **given** the reverse (three statement lines, one ledger entry), it proposes that too. |
| AC-04.5 | **Given** more than 8 plausible components, **then** only the 8 best candidates by the deterministic order are considered, and the run records `TIER4_CANDIDATES_TRUNCATED` in its output. |
| AC-04.6 | **Given** two subsets that both sum to the target, **then** no auto-confirm happens; the item goes to review with both subsets as candidates. |
| AC-04.7 | **Given** any stored match, **then** `tier`, `score` and `reason` are present and the reason is non-empty. |
| AC-04.8 | **Given** the hard-negative corpus (look-alikes that must not match), **then** none is auto-confirmed (NFR-02). |

Edge cases:
- **EC-04.1 Same-amount collisions**: two statement lines and two ledger entries of equal amount, different references: Tier 1 or 2 resolves them by reference; if references also tie, FRD-05 tie rule applies.
- **EC-04.2 Currency mismatch**: same reference and amount, different currency: never a match; produces a `CURRENCY_MISMATCH` exception with the candidate attached.
- **EC-04.3 Amount mismatch**: same reference and date, different amount: never a match; `AMOUNT_MISMATCH` exception with the delta in minor units.
- **EC-04.4 Reversal pairs**: `+100.00` and `-100.00` with the same reference are **not** matched to each other; each looks for its own counterpart.
- **EC-04.5 Duplicate references**: a reference appearing twice on one side and once on the other: one candidate pairing is claimed per FRD-05, the other line becomes `DUPLICATE_SUSPECT`.
- **EC-04.6 Weekend dates**: rolled to Monday before distance is computed.
- **EC-04.7 Zero candidates**: skipped to exception creation, never an error.

### FRD-05 Confidence bands, claim resolution, determinism rules (FR-05, Must)

| AC | Given / When / Then |
| --- | --- |
| AC-05.1 | **Given** a match with `score >= autoThreshold`, **then** it is auto-confirmed. |
| AC-05.2 | **Given** `reviewThreshold <= score < autoThreshold`, **then** it is not confirmed; an `ExceptionCase(kind=REVIEW)` is created holding the proposed match and the top 3 candidates in rank order. |
| AC-05.3 | **Given** `score < reviewThreshold` or no candidate, **then** an `ExceptionCase(kind=BREAK)` is created (FRD-06). |
| AC-05.4 | **Given** a candidate claimed by one match, **then** it cannot be claimed by another in the same or a later run until unmatched. |
| AC-05.5 | **Given** several candidates, **then** ordering is: score descending, then ledger value date ascending, then ledger `seq` ascending, then ledger `lineKey` ascending. |
| AC-05.6 | **Given** an unbreakable tie (equal best score for two different candidates of one line, or one candidate equally best for two lines), **then** nothing is auto-confirmed; the affected lines go to review with reason `AMBIGUOUS_TIE`. |
| AC-05.7 | **Given** an operator changes a threshold, **then** the change is validated (`0 < review < auto <= 1`), versioned as a new `configVersion`, and written to the audit log with actor and reason. |
| AC-05.8 | **Given** thresholds, **then** they are read from one configuration object; no literal threshold exists in matching code (ArchUnit and code-search check). |

Edge cases: **EC-05.1** score exactly equal to a threshold (boundary tests at 0.95, 0.70, 0.9499, 0.6999); **EC-05.2** auto threshold lowered to include Tier 3 (allowed, audited, flagged in run output).

### FRD-06 Exception creation and routing (FR-06, Must)

| AC | Given / When / Then |
| --- | --- |
| AC-06.1 | **Given** a statement line with no match above the review threshold, **then** an exception `MISSING_LEDGER` (or a more specific type, see below) is created in state `OPEN` with amount, currency, age zero, priority and a suggested owner team. |
| AC-06.2 | **Given** a ledger entry still unmatched when its value date falls outside the lookback window, **then** `MISSING_STATEMENT` is created (Q-05). |
| AC-06.3 | **Given** a run is re-executed, **then** no duplicate exception is created for the same line (exception natural key: `lineKey` plus kind). |
| AC-06.4 | **Given** routing rules, **then** the first rule that matches in the fixed order below sets the team; the matched rule name is stored. |

Exception types: `MISSING_LEDGER`, `MISSING_STATEMENT`, `AMOUNT_MISMATCH`, `CURRENCY_MISMATCH`, `DUPLICATE_SUSPECT`, `LOW_CONFIDENCE` (review band), `AMBIGUOUS_TIE`.

Routing (first match wins):

| Order | Condition | Team | Priority |
| --- | --- | --- | --- |
| 1 | `CURRENCY_MISMATCH` | `FX_TEAM` | P2 |
| 2 | absolute amount above the high-value threshold for its currency | `SENIOR_ANALYST` | P1 |
| 3 | `MISSING_LEDGER` | `LEDGER_OPERATIONS` | P3 |
| 4 | `MISSING_STATEMENT` | `BANK_OPERATIONS` | P3 |
| 5 | `AMOUNT_MISMATCH`, `LOW_CONFIDENCE`, `AMBIGUOUS_TIE`, `DUPLICATE_SUSPECT` | `RECON_ANALYSTS` | P3 (P4 for `DUPLICATE_SUSPECT` below high value) |
| 6 | SLA breach (set by escalation, FRD-07) | `TEAM_LEAD` | raised one level |

SLA clock (Q-10): P1 1, P2 2, P3 5, P4 10 business days; at risk at 75 percent consumed; configurable.

Edge cases: **EC-06.1** high-value threshold missing for a currency (default used, warning logged); **EC-06.2** exceptions created in a run that later fails (run is transactional, so none persist).

### FRD-07 Exception state machine (FR-07, Must)

States: `OPEN`, `ASSIGNED`, `INVESTIGATING`, `RESOLVED`, `ESCALATED`. `RESOLVED` is terminal (Q-08).

| From | To | Trigger | Who | Notes |
| --- | --- | --- | --- | --- |
| `OPEN` | `ASSIGNED` | assign, or routing rule | `TEAM_LEAD`, system | Owner required |
| `ASSIGNED` | `INVESTIGATING` | start work | assigned owner | Notes and attachment metadata recorded |
| `INVESTIGATING` | `RESOLVED` | manual match, split, adjustment, write-off | owner (maker) | Controlled actions above threshold need approval (FRD-08) |
| `INVESTIGATING` | `ESCALATED` | escalate | owner, `TEAM_LEAD`, system on SLA breach | Reason required |
| `OPEN` | `ESCALATED` | SLA breach | system | Unworked item |
| `ASSIGNED` | `ESCALATED` | SLA breach | system | Unworked item |
| `ESCALATED` | `INVESTIGATING` | senior takes it | `TEAM_LEAD` or senior owner | Original owner stays on record |
| `ESCALATED` | `ASSIGNED` | re-assign to a higher owner | `TEAM_LEAD` | Original owner stays on record |

Everything else is illegal. While a controlled action awaits approval the case stays `INVESTIGATING` with a pending `ApprovalRequest`; no new state is introduced.

| AC | Given / When / Then |
| --- | --- |
| AC-07.1 | **Given** each legal row above, **then** the transition succeeds and writes an audit event with before and after state. |
| AC-07.2 | **Given** any pair not in the table (for example `RESOLVED -> OPEN`, `OPEN -> RESOLVED`, `OPEN -> INVESTIGATING`), **then** it fails with `ILLEGAL_STATE_TRANSITION` and nothing changes. |
| AC-07.3 | **Given** an exception moved to `ESCALATED`, **then** the previous owner is retained in the assignment history. |
| AC-07.4 | **Given** two users acting on the same case concurrently, **then** one succeeds and the other receives a conflict (optimistic locking on a version column). |
| AC-07.5 | **Given** the transition table, **then** a test enumerates all 25 ordered state pairs and asserts exactly the 8 legal ones succeed. |

Edge cases: **EC-07.1** transition by a user lacking the role (rejected, FRD-08 and Phase 8); **EC-07.2** system-driven escalation raced by a manual one (idempotent, second becomes a no-op with a conflict-free outcome).

### FRD-08 Maker-checker (FR-08, Must)

Controlled actions: `WRITE_OFF` and `ADJUSTMENT` whose absolute amount is **strictly greater** than the configured threshold for the currency (Q-09).

| AC | Given / When / Then |
| --- | --- |
| AC-08.1 | **Given** a controlled action above threshold, **when** the maker submits it, **then** an `ApprovalRequest` is created `PENDING` and the case is not resolved. |
| AC-08.2 | **Given** a pending request, **when** a user with the controller role who is **not the maker** approves, **then** the request is `APPROVED`, the case becomes `RESOLVED`, and maker, action, reason, checker, decision and timestamps are all audited. |
| AC-08.3 | **Given** the checker equals the maker, **then** the decision is rejected with `SEPARATION_OF_DUTIES_VIOLATION` and audited as a denied attempt. |
| AC-08.4 | **Given** a rejection, **then** the request is `REJECTED` with a mandatory reason and the case stays `INVESTIGATING`. |
| AC-08.5 | **Given** a controlled action at or below the threshold, **then** no approval is needed and the owner resolves directly (still audited). |
| AC-08.6 | **Given** a decided request, **then** it cannot be decided again. |

Edge cases: **EC-08.1** threshold changed while a request is pending (the threshold in force at submission applies, stored on the request); **EC-08.2** maker reassigned or deactivated (request stays valid, any other controller may decide); **EC-08.3** amount exactly equal to threshold (no approval).

### FRD-09 Append-only hash-chained audit (FR-09, Must)

| AC | Given / When / Then |
| --- | --- |
| AC-09.1 | **Given** any exception state change, approval event, manual match action or configuration change, **then** exactly one audit event is appended in the same database transaction as the change. |
| AC-09.2 | **Given** event `n`, **then** `currentHash = SHA-256(canonical(event n fields) || previousHash)` and `previousHash` equals the `currentHash` of event `n-1` (genesis uses 32 zero bytes). |
| AC-09.3 | **Given** an untouched chain, **when** `audit verify` runs, **then** it reports `OK` with the event count and head hash. |
| AC-09.4 | **Given** one stored field of one event is edited directly in the database, **then** verify fails and reports the first broken sequence number and the reason (`HASH_MISMATCH` or `CHAIN_BREAK`). |
| AC-09.5 | **Given** a deleted or inserted-in-the-middle event, **then** verify detects it. |
| AC-09.6 | **Given** an attempted `UPDATE` or `DELETE` on the audit table by the application role, **then** the database refuses it. |
| AC-09.7 | **Given** two concurrent appends, **then** the chain stays linear with no gaps and no forks. |

Edge cases: **EC-09.1** timestamp precision (stored and hashed as epoch microseconds, so a database round-trip cannot change the hash); **EC-09.2** very large before/after payloads (stored as canonical JSON, size limit); **EC-09.3** a privileged database owner recomputing the entire chain is not prevented by the chain alone, so the head hash is also recorded in each run's output and logs as an external anchor (see ADR-0004).

### FRD-10 Deterministic replay (FR-10, Must)

| AC | Given / When / Then |
| --- | --- |
| AC-10.1 | **Given** a completed run, **then** its stored record includes `businessDate`, `configVersion`, input batch ids, `ledgerCutoffSeq`, counts, duration and `outputHash`. |
| AC-10.2 | **Given** a completed run, **when** replayed, **then** the engine reads the stored inputs as of the recorded cut-off and produces an `outputHash` identical to the stored one. |
| AC-10.3 | **Given** `outputHash = SHA-256` over the canonical output (matches then exceptions, each in the total order below, fields in fixed order, UTF-8, `\n` line ends), **then** two runs on identical inputs produce byte-identical canonical output. |
| AC-10.4 | **Given** input rows supplied in shuffled order, **then** the output hash is unchanged. |
| AC-10.5 | **Given** the engine is run on different JVM instances or thread counts, **then** the output hash is unchanged. |
| AC-10.6 | **Given** replay is requested but the stored inputs or configuration version are missing, **then** it fails loudly with `REPLAY_INPUTS_UNAVAILABLE`, never producing a "close enough" result. |

Output total order: matches by `(statementLineKey, tier, ledgerLineKey...)`; exceptions by `(kind, lineKey)`. Replay does not write new matches; it computes and compares.

Edge cases: **EC-10.1** configuration changed since the run (replay uses the stored `configVersion`); **EC-10.2** entries ingested after the run (excluded by cut-off); **EC-10.3** time must never leak into output: run start and end timestamps are metadata excluded from the hash.

### FRD-11 Ageing and SLA reporting (FR-11, Should)

| AC | Given / When / Then |
| --- | --- |
| AC-11.1 | **Given** open exceptions and an as-of business date, **then** the report counts them in buckets `0-1`, `2-5`, `6-10`, `>10` business days (Q-06), by team and by exception type. |
| AC-11.2 | **Given** each exception, **then** SLA status is `WITHIN_SLA`, `AT_RISK` (75 percent or more consumed) or `BREACHED`. |
| AC-11.3 | **Given** a breach, **then** the escalation job moves it to `ESCALATED` once (FRD-07) and emits `sla_breach_count`. |
| AC-11.4 | **Given** the same data and as-of date, **then** the report is identical on every call (no wall-clock reads). |
| AC-11.5 | **Given** resolved exceptions, **then** they are excluded from ageing and included in resolution-time statistics. |

Reports (Phase 6): match report, tier report, ageing report, SLA report, team workload.

### FRD-12 Manual match, split, unmatch, re-match (FR-12, Should)

| AC | Given / When / Then |
| --- | --- |
| AC-12.1 | **Given** an `INVESTIGATING` case, **when** the owner manually matches a line to a ledger entry, **then** a match with tier `MANUAL`, score 1.00 and the analyst's reason is stored, the case resolves, and the action is audited. |
| AC-12.2 | **Given** a statement line and several entries, **when** the owner splits, **then** the components must sum exactly to the line amount or the action is rejected. |
| AC-12.3 | **Given** a confirmed match, **when** a user unmatches it, **then** a reversal row is written (the original match is never deleted), both sides return to the pool, and a **new** linked exception is created (Q-08). |
| AC-12.4 | **Given** an unmatched pair, **when** re-matched, **then** the new match follows the same claim rules (a candidate has at most one active match). |
| AC-12.5 | **Given** a manual match above the high-value threshold that needs an adjustment to balance, **then** the adjustment follows FRD-08. |

### FRD-13 Kafka event publication (FR-13, Could, Phase 7)

| AC | Given / When / Then |
| --- | --- |
| AC-13.1 | **Given** an exception event, **then** a row is inserted into the outbox in the same transaction as the business change. |
| AC-13.2 | **Given** committed outbox rows, **then** a publisher delivers them at least once to a versioned topic (for example `payments.exception.created.v1`). |
| AC-13.3 | **Given** Kafka is down, **then** business operations still succeed and the outbox backlog grows and is observable. |
| AC-13.4 | **Given** a duplicate delivery, **then** consumers deduplicate on `eventId` and perform the business effect once. |

### FRD-14 Read-only dashboard (FR-14, Could, Phase 9)

| AC | Given / When / Then |
| --- | --- |
| AC-14.1 | **Given** current data, **then** the dashboard shows queue size, ageing buckets and the latest match rate, read-only, from the same report endpoints as the API. |

## 6. Non-functional requirement hooks

| NFR | Where it lands in this FRD |
| --- | --- |
| NFR-01 throughput | FRD-04 algorithms are bounded (Tier 4 at 8 candidates, hash-indexed Tiers 1 and 2) |
| NFR-02 correctness | AC-04.8, FRD-05 tie rule, [synthetic-data-spec.md](synthetic-data-spec.md) |
| NFR-03 idempotency | AC-01.2, AC-01.3 |
| NFR-04 determinism | FRD-10 |
| NFR-05 money safety | FRD-02, ADR-0001 |
| NFR-06 auditability | AC-09.4, AC-09.5 |
| NFR-07 observability | FRD-11 plus metric list in architecture.md |
| NFR-08 coverage | CI gate, applies to `domain` and `matching` |

## 7. Error model (preview)

All errors share one JSON shape: `code`, `message`, `correlationId`, `timestamp`, optional `details[]` of `field` and `issue`. No stack traces, SQL, hostnames or secrets. Codes introduced so far: `INGESTION_EMPTY_FILE`, `INGESTION_UNSUPPORTED_FORMAT`, `AMOUNT_PRECISION`, `AMOUNT_RANGE`, `LEDGER_ENTRY_CONFLICT`, `ILLEGAL_STATE_TRANSITION`, `SEPARATION_OF_DUTIES_VIOLATION`, `REPLAY_INPUTS_UNAVAILABLE`, `CONFIG_INVALID`, `CONCURRENT_MODIFICATION`. The full catalogue ships with the OpenAPI spec ([api/README.md](api/README.md)).
