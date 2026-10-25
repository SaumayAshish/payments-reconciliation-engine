# ADR-0006: Tier 4 candidate bound stays at 8

Status: Accepted, 2026-10-25

## Context

Tier 4 (one-to-many) searches subsets of at most `maxOneToManyCandidates` unmatched lines inside the date window. The BRD sets the default at 8; `MatchingConfig` allows 2 to 12. When more lines are in the window than the cap allows, the search is truncated, a truncation note is written to the run, and the batch is not matched. A truncated search can therefore lose a one-to-many match, but it cannot create a match.

Open question from Phase 3: should the default stay at 8, should candidates be ranked (for example by shared reference) before the cut, or should the default be raised?

## Evidence

Measured with `Tier4RecallProbe` (seed 20261022, default configuration apart from the cap). The crowded layout puts batches in the same accounts and dates as everything else. "Ok/total" counts truth cases handled as labelled. Density is lines per business day: the standard spread is 50.

| Layout | Lines | Business days | Cap | One-to-many ok/total | Subset ambiguity ok/total | Truncation notes | False auto-matches |
|---|---|---|---|---|---|---|---|
| Isolated | 3,000 | 60 | 8 | 11/11 | 2/2 | 0 | 0 |
| Crowded | 3,000 | 60 | 8 | 11/11 | 2/2 | 2 | 0 |
| Crowded | 3,000 | 60 | 12 | 11/11 | 2/2 | 0 | 0 |
| Crowded | 3,000 | 20 | 8 | 11/11 | 2/2 | 4 | 0 |
| Crowded | 3,000 | 20 | 12 | 11/11 | 2/2 | 0 | 0 |
| Crowded | 3,000 | 10 | 8 | 11/11 | 2/2 | 22 | 0 |
| Crowded | 3,000 | 10 | 12 | 11/11 | 2/2 | 2 | 0 |
| Crowded | 20,000 | 400 | 8 | 79/80 | 18/18 | 6 | 0 |
| Crowded | 20,000 | 400 | 12 | 80/80 | 18/18 | 2 | 0 |

Reproduce: `mvn -Pbench test-compile`, then `java -Xmx8g -cp $cp com.saumay.payments.matching.corpus.Tier4RecallProbe` (classpath as in `docs/benchmarks.md`).

## Decision

Keep the default at 8 and keep the current candidate selection. Do not add ranking.

## Consequences

- Safety is unaffected by the bound: no row shows a false auto-match.
- At realistic density the bound costs 1 one-to-many case in 80 (98.75 percent) in the largest crowded corpus. The lost case is reported through a truncation note and becomes an exception for a person to resolve.
- An operator who sees many truncation notes can raise the cap up to 12 through audited configuration (FR-05). At 12 the same corpus loses none.
- Revisit if real data shows sustained truncation: the first lever is the configured cap, the second is ranking candidates, and ranking would need its own ADR with evidence.

## Alternatives considered

- **Rank candidates by shared reference before the cut.** Rejected for now: it adds a heuristic to candidate selection and nothing in the evidence shows a need. The loss it would address is one case in 80, and only in the crowded layout.
- **Raise the default to 12.** Rejected: the BRD fixes the default at 8, and a larger cap enlarges the subset search space for every run to rescue a rare case. It remains available as an audited configuration change.

## Interview explanation

"Tier 4 searches subsets, so I bound it. I measured what the bound costs on a deliberately crowded corpus: it never produced a wrong auto-match, and at realistic density it missed one batch in eighty, which surfaces as an exception for a human. So I kept the BRD default, left the knob configurable and audited, and wrote down the evidence rather than adding a ranking heuristic I couldn't justify."