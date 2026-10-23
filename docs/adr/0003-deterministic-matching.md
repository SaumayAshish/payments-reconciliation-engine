# ADR-0003: Deterministic matching and claim resolution

- Status: **Accepted** (approved by Caesar, 2026-10-07; scoring formulas in ADR-0003 remain tunable in Phase 2 via amendment)
- Date: 2026-10-07
- Related: FR-04, FR-05, FR-10, FRD-04, FRD-05, FRD-10, NFR-02, NFR-04, Q-02, Q-03, Q-04, Q-07

## Context

An auditor must be able to re-run a day and get the same answer, and a regression must show up as a diff, not as noise. Nondeterminism in matching typically comes from hash-map iteration order, unspecified database row order, thread scheduling, wall-clock reads and random identifiers. It also matters for correctness: when two ledger entries look equally good, the engine must not pick one by accident.

## Decision

### Inputs are explicit and immutable
A run is defined by `(businessDate, configVersion, statementBatchIds, ledgerCutoffSeq)` (Q-07). Input rows are loaded `ORDER BY seq`. Candidate ledger entries are those with `seq <= ledgerCutoffSeq`, same account, not already actively matched by an earlier run, with value date inside the lookback window. Confirmed matches are never deleted; an unmatch writes a reversal row with its own sequence number, so "the state before run R" is always reconstructible.

### The engine is a pure function
`Output = f(inputs, config)`. No wall clock, no randomness, no I/O inside `matching`. Time enters only as run metadata, which is excluded from the output hash. ArchUnit forbids `Instant.now`, `Random`, `UUID.randomUUID` and unordered collection iteration patterns in the package.

### Tiers run in order and are evaluated as a set, not row-by-row
For each tier, the engine generates **all** candidate pairings with scores, sorts them by a total order, then accepts greedily. The result therefore cannot depend on the order statement lines happened to arrive in.

Total order for candidates of one statement line:
1. score descending
2. ledger value date ascending ("earliest ledger entry")
3. ledger `seq` ascending
4. ledger `lineKey` ascending (final deterministic tie-breaker)

### Claim rule
A ledger entry (or statement line) can be claimed by **at most one active match**. Enforced twice: in the resolver, and by a partial unique index in PostgreSQL.

### Ambiguity is never auto-confirmed
If a line's best score is shared by two different candidates, or one candidate is the best for two different lines with equal score, none of those pairings is confirmed. They go to review with reason `AMBIGUOUS_TIE` and the top three candidates. The total order above is used to **rank and display** candidates, never to silently pick a winner for an auto-confirm when scores are equal.

### Scoring uses integers
Scores are scaled integers (`scoreMilli`). Bands: `>= 950` auto, `700 to 949` review, `< 700` exception. Formulas for Tiers 2 to 4 are defined in FRD-04 and are **proposed**; they are tuned against the labelled corpus in Phase 2 and any change is an amendment to this ADR before the gate. Tier 3 and 4 are capped at 949 so they cannot auto-confirm by default (Q-03).

### Bounded search for one-to-many
Tier 4 considers at most 8 candidate entries (default, configurable), chosen by the candidate order, then evaluates all subsets of size 2 to 8 (at most 247 subsets) for an exact sum. Truncation is recorded in the run output. Multiple exact subsets are an ambiguity and go to review.

### Output hash
`outputHash = SHA-256(canonical output)`. Canonical output is UTF-8 text, one record per line, `\n` terminated, fields in fixed order, matches then exceptions, each sorted by a fixed key (FRD-10). Replay recomputes from stored inputs and the recorded config version and must produce the same hash.

## Alternatives considered

| Alternative | Why rejected |
| --- | --- |
| Process lines sequentially, first match wins | Result depends on arrival order; cannot be replayed from a shuffled input |
| Break ties by lowest database id | Ties become silently auto-confirmed, which risks false matches (NFR-02) |
| Random or hash-based tiebreak | Deterministic but arbitrary, so it hides real ambiguity from a reviewer |
| Allow parallel matching with nondeterministic merge | Fails NFR-04. Parallelism is allowed only where the merge is order-independent (partition by account, merge by the total order) |
| Floating-point scores | Boundary comparisons (`>= 0.95`) become fragile |

## Consequences

- Replay is a first-class test: shuffled input, different thread counts and different JVMs must give the same hash (AC-10.4, AC-10.5).
- The engine does more up-front work per tier (generate all candidate pairs) than a naive loop. Candidate generation is indexed by `(account, currency, amount)`, which keeps it near-linear; measured in Phase 3.
- Strictness on ties may increase the review queue for genuinely identical duplicate payments. Accepted for v1; an "interchangeable group" optimisation would need its own ADR.

## Interview explanation

"Reconciliation has to be reproducible, so the engine is a pure function of explicit inputs and configuration. I rank candidates by a total order, resolve claims globally rather than row by row, and never auto-confirm a tie, because an unlucky tiebreak is a false match in a bank."

## Amendment 2026-10-21: tie resolution inside a tier, review claims, weekend gap

1. Within a tier, pairs are processed in groups of equal score, best first. In a group a pair is accepted only if neither line appears in another live pair of that group. Otherwise the whole connected cluster of lines is held back as `AMBIGUOUS_TIE` and takes no part in lower-scored groups or lower tiers. The outcome depends only on sets of lines, never on arrival order; a shuffle test enforces it.
2. A review-band proposal (for example Tier 2 at 0.93) claims both lines as a tentative match, so it keeps the "at most one active match" rule.
3. Tier 2 admits business-day distance 0 when the value dates differ (a weekend date rolls to Monday), closing a gap in FRD-04, which required distance of at least 1.
4. In Tier 4, any two proposals that share a line are held back together as one AMBIGUOUS_TIE group regardless of score (stricter than the per-score rule in Tiers 1–3), because choosing which lines form a batch is a human decision (AC-04.6). Truncated candidate searches are recorded as TIER4_CANDIDATES_TRUNCATED notes on TierOutcome (AC-04.5).
Rejected: greedy "take the first by total order" (silently picks a winner among equals, contradicting AC-05.6), and holding back only the contested pair while the lines fall through to worse candidates (a reviewer may rightly want the better one). Consequence: genuinely identical duplicate payments go to review.
5. Tier 4 candidate bound (measured). The 8-candidate cap is by nearest value date, then canonical order. When other unmatched lines crowd one account and date window, the true components can fall outside the cap, so a real batch is missed and a genuine two-subset ambiguity can look like a single proposal. This can never auto-confirm, because tier 4 is capped below the auto threshold. The truncation is recorded as a run note and in the proposal’s reason. Measured on 10 seeds of 3,000 lines with crowded batches: 129/129 found at 60 business days of spread, 126/129 at 30, 121/129 at 10. Alternatives to evaluate with the 100K and 1M runs: rank candidates by shared reference first, or raise the cap.