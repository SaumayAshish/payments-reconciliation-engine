# Synthetic Data Specification

Status: **Draft for Phase 0 review.** Implements the corpus used by NFR-02 (zero false auto-matches), NFR-04
(determinism), the Phase 2 match-rate gate and the throughput runs for NFR-01.

All data is synthetic. No real bank, customer, employer or account data is used anywhere. Counterparty names come from a
fixed invented list (for example `Northwind Trading`, `Harbor Freight Supplies`); account ids look like
`NOSTRO-GBP-001`.

## 1. Goals

1. **Known ground truth.** Every generated statement line and ledger entry carries a hidden label: its true counterpart
   (s) or the fact that it has none. Tests compare engine output with the label.
2. **Determinism.** The same `(seed, size, profile)` produces byte-identical files. The generator uses a documented PRNG
   (SplittableRandom with the seed, never `Math.random`, never time).
3. **Coverage of every tier and every failure mode**, including look-alikes that must **not** match.
4. **Scalable.** Profiles for 1K, 10K, 100K and 1M lines with the same proportions.

## 2. Outputs

For a profile the generator writes to a target directory:

| File                    | Content                                                                                                                 |
|-------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `statement.csv`         | Statement lines in the CSV format of FRD section 4.2                                                                    |
| `ledger.csv`            | Ledger entries in the format of FRD section 4.3                                                                         |
| `statement.camt053.xml` | Same statement lines as simplified camt.053 (for parser tests)                                                          |
| `ground-truth.csv`      | One row per statement line and per ledger entry: `lineKey`, `side`, `truth`, `truthTier`, `counterpartKeys`, `scenario` |
| `manifest.json`         | Seed, profile, counts per scenario, generator version, SHA-256 of each file                                             |

`truth` is one of `MATCH`, `ONE_TO_MANY`, `NO_COUNTERPART`, `MUST_NOT_MATCH`. `scenario` names the generator rule below.
Ground truth is never read by the engine; only by test assertions.

## 3. Base population

Ground truth is created **first**, then noise is applied to produce the second side. This guarantees the label is
correct by construction.

1. Draw `N` "true transactions" with: account (from a fixed set of 6 across GBP, EUR, USD, JPY, KWD, INR), amount
   (log-normal, in minor units, respecting currency exponent), value date (spread over 20 business days), reference
   (pattern `INV-YYYY-NNNNNN`, `PAY/NNNN/XX`, `SWF...`), counterparty (from the fixed list), narrative (templated text
   with the counterparty).
2. Emit each as a statement line and, according to its scenario, as one or more ledger entries.

## 4. Scenarios and target proportions

Proportions are of statement lines and live in a profile file, so changing them is configuration, not code. The values
below are a **placeholder profile** chosen so the BRD's 90 percent target is reachable under the recommended definition
in Q-02 (match rate = auto-confirmed lines / total lines). They are tuned only before the Phase 2 gate, and every change
is recorded in the manifest. They are not a claim about real-world data.

| Scenario          | Truth               | Proportion                                               | How it is generated                                                                                                               | Expected engine outcome   |
|-------------------|---------------------|----------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|---------------------------|
| `EXACT`           | MATCH, tier 1       | 82%                                                      | Ledger entry identical on reference, amount, currency, value date                                                                 | Auto-confirm, score 1.00  |
| `TOLERANCE_1D`    | MATCH, tier 2       | 10%                                                      | Same, ledger value date shifted by 1 business day                                                                                 | Auto-confirm, score 0.96  |
| `TOLERANCE_2D_3D` | MATCH, tier 2       | 2%                                                       | Shifted by 2 or 3 business days                                                                                                   | Review band (0.93, 0.90)  |
| `FUZZY`           | MATCH, tier 3       | 2%                                                       | Reference missing or different, counterparty with case, punctuation and legal-suffix variants and one typo, narrative paraphrased | Review band               |
| `ONE_TO_MANY`     | ONE_TO_MANY, tier 4 | 1%                                                       | One statement line equals the sum of 2 to 6 ledger entries (some in the reverse direction)                                        | Review band               |
| `NO_LEDGER`       | NO_COUNTERPART      | 1.5%                                                     | Statement line with no ledger entry                                                                                               | `MISSING_LEDGER` break    |
| `HARD_NEGATIVE`   | MUST_NOT_MATCH      | 1.5%                                                     | See section 5                                                                                                                     | Never auto-confirmed      |
| `NO_STATEMENT`    | NO_COUNTERPART      | extra ledger-side entries equal to 1% of statement lines | Ledger entry with no statement line                                                                                               | `MISSING_STATEMENT` break |

The first seven rows sum to 100 percent of statement lines. `NO_STATEMENT` adds ledger-only entries on top.

Expected outcomes under the default thresholds and proposed scoring (FRD-04):

| Measure                                                              | Expected  |
|----------------------------------------------------------------------|-----------|
| Auto-confirmed (`EXACT` + `TOLERANCE_1D`)                            | about 92% |
| Matched in any band (adds tolerance 2 to 3 days, fuzzy, one-to-many) | about 97% |
| Breaks and hard negatives                                            | about 3%  |

**Why this matters.** With Tier 3 and 4 capped below the auto threshold (Q-03) and two- and three-day tolerance matches
landing in review, the auto-confirmed share is almost entirely exact plus one-day matches. If real-world-like data has
fewer exact matches than 82 percent, the 90 percent auto-confirm target is not met without lowering the auto threshold.
That trade-off (more automation versus more false-match risk) is exactly what Q-02 and Q-03 ask you to decide. The
README will report the measured rate for the profile actually used, with the seed.

## 5. Hard negatives (must not match)

Look-alike pairs generated on purpose. Each is labelled `MUST_NOT_MATCH`; the assertion is that **no hard negative is
ever auto-confirmed** and, where the rule says so, that it is not matched at all.

| Hard negative                 | Construction                                                                                                                                                  | Required outcome                                                                                   |
|-------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|
| `SAME_AMOUNT_DIFFERENT_REF`   | Same amount, currency and date as a true pair, different unrelated reference and counterparty                                                                 | Not auto-confirmed                                                                                 |
| `SAME_REF_DIFFERENT_AMOUNT`   | Same reference and date, amount differs by 1 minor unit                                                                                                       | No match; `AMOUNT_MISMATCH`                                                                        |
| `SAME_REF_DIFFERENT_CURRENCY` | Same reference and amount, different currency                                                                                                                 | No match; `CURRENCY_MISMATCH`                                                                      |
| `OUT_OF_WINDOW`               | Same reference, amount, currency; value date 4+ business days apart                                                                                           | Not matched in Tier 2                                                                              |
| `REVERSAL_PAIR`               | `+X` and `-X` with the same reference                                                                                                                         | Never matched to each other                                                                        |
| `DUPLICATE_REFERENCE`         | Same reference appears twice on one side, once on the other                                                                                                   | No pairing is confirmed; all lines involved form one AMBIGUOUS_TIE review group (AC-05.6).         |
| `IDENTICAL_TWINS`             | Two identical statement lines and two identical ledger entries                                                                                                | `AMBIGUOUS_TIE` review, never auto-confirmed                                                       |
| `FUZZY_NEAR_MISS`             | Similar counterparty and amount but a different customer (for example `Northwind Trading` vs `Northwind Traders`) with a different true counterpart elsewhere | Not auto-confirmed                                                                                 |
| `SUBSET_AMBIGUITY`            | Two different subsets of ledger entries sum to the statement amount                                                                                           | Review with both subsets, never auto-confirmed                                                     |
| `CROSS_ACCOUNT`               | Identical fields in a different account                                                                                                                       | No match                                                                                           |
| `WEEKEND_DATE`                | Value date on Saturday or Sunday, true counterpart on the following Monday                                                                                    | Tier 2 with distance 0 treated as exact date after roll-forward, or documented behaviour; asserted |
| `NEAR_THRESHOLD`              | Constructed to score at 0.9499 and 0.9500                                                                                                                     | 0.9499 review, 0.9500 auto                                                                         |

## 6. Data-quality defects (for ingestion tests)

Injected into a separate **defect file set**, not the main corpus, each with an expected error code:

missing reference, invalid currency code, impossible date, non-numeric amount, amount with too many decimals for the
currency, zero amount, amount overflow, wrong column count, unterminated quote, BOM and CRLF variants, header only,
empty file, non-UTF-8 bytes, camt.053 with DTD or external entity (must be refused), camt.053 truncated mid-element.

## 7. Properties the corpus must satisfy (self-checks)

The generator test asserts, for every profile:

1. Same seed gives identical files and manifest hash.
2. Ground truth is internally consistent: every `MATCH` references existing keys on the other side; every `ONE_TO_MANY`
   group sums exactly; no ledger entry is the truth of two lines.
3. Scenario counts equal the configured proportions within rounding.
4. No line is a duplicate of another unless the scenario says so (`IDENTICAL_TWINS`, `DUPLICATE_REFERENCE`).
5. Amounts respect each currency's exponent; no zero amounts.

## 8. How tests use it

| Test                             | Uses                                                                                              |
|----------------------------------|---------------------------------------------------------------------------------------------------|
| NFR-02 zero false auto-matches   | Any engine-confirmed pair that disagrees with ground truth fails the build                        |
| Match rate and tier distribution | Computed by comparing engine output to ground truth; published in the README with the corpus seed |
| NFR-04 determinism               | Shuffle both inputs with several seeds, run with 1 and N threads, compare output hashes           |
| NFR-01 throughput                | 1M-line profile, timed end to end, hardware recorded in `docs/benchmarks.md`                      |
| Ingestion idempotency            | The same file ingested 10 times, counts compared                                                  |
| Property tests                   | Normalisation and money properties use random inputs seeded explicitly                            |

## 9. Generator design constraints

- Lives in test or tooling code, not in `domain` or `matching`.
- Pure and seeded; no network, no clock.
- Streams output so 1M lines do not need to fit in memory.
- Versioned: the manifest records the generator version so a corpus change is traceable in the benchmark history.

## 10. Implementation notes, generator v1":

In-memory corpus only; the CSV and camt.053 file export is deferred. Amounts are log-uniform by digit count using
integer arithmetic (no floating point), not log-normal. Value dates spread over max (20, lines/50) business days. There
are 7 ordinary accounts and 6 batch accounts. In the default layout each batch gets its own account and a date at least
14 days from any other batch there. Ordinary amounts are all 1 mod 11, so a sum of up to 12 of them can never equal
another ordinary amount by accident. The truth model uses expected outcomes (auto exact, auto tolerance, review
tolerance, review fuzzy, review one-to-many, ambiguous, unmatched) per case. NEAR_THRESHOLD is covered by
MatchingConfigTest, not the corpus.

## Amount ranges and generator capacity (added 2026-10-25)

Every ordinary amount is unique across the corpus and congruent to 1 modulo 11. A range of d digits therefore holds only about 0.09 * 10^d such values, and all scenarios share them. The 2-to-6-digit range holds about 91,000 values, which capped the generator at roughly 130,000 statement lines; the first 1M-line benchmark found this when the generator looped forever.

The generator now widens a draw by one digit (up to 12) after 10,000 consecutive failed attempts. Below that point the random sequence is unchanged, so every corpus that could be built before is identical (the 100,000-line output hash is the same before and after the change). Corpora of 1,000,000 lines now generate; the 1M corpus took 190 seconds to build on the benchmark machine (`docs/benchmarks.md`).