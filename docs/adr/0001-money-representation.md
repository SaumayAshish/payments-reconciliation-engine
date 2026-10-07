# ADR-0001: Money is signed minor units plus an ISO 4217 currency

- Status: **Proposed** (Phase 0, awaiting approval)
- Date: 2026-10-07
- Related: FRD-02, NFR-05, Q-01

## Context

Every decision the engine makes compares or sums amounts. Floating point cannot represent most decimal fractions exactly (`0.1 + 0.2 != 0.3`), so a threshold comparison or a one-to-many sum computed in `double` can be wrong by a unit in the last place and silently turn a true match into a break or, worse, a false match. Currencies also differ in exponent (JPY has 0 decimals, GBP 2, KWD 3), so "an amount" is meaningless without its currency.

## Decision

1. All monetary values are a `Money` value object holding a signed `long` count of **minor units** and a `CurrencyCode` (ISO 4217 alpha-3).
2. `Money` is immutable. Arithmetic uses `Math.addExact`, `Math.subtractExact` and `Math.negateExact`; overflow throws. Adding or comparing different currencies throws. There is no implicit conversion and no FX in v1.
3. Amounts enter the system only as **decimal text** (CSV, XML, JSON strings) and are converted exactly: `BigDecimal` is permitted **only** in the `ingestion.normalise` package. Fractional digits beyond the currency's exponent are **rejected**, never rounded. The exponent comes from `java.util.Currency#getDefaultFractionDigits`.
4. `double`, `float` and their wrappers are forbidden in `domain`, `matching` and the normaliser output. An ArchUnit rule and a bytecode-level test enforce it (NFR-05).
5. Zero amounts are rejected at ingestion (a zero line carries no reconciliation meaning and breaks "sum of components" reasoning).
6. Scores and thresholds are also not floating point: they are **scaled integers** (`scoreMilli`, 0 to 1000). `0.95` is `950`. The `0.949` ceiling in the FRD is `949`.
7. Sign convention: money in is positive, money out negative, from the account owner's perspective (Q-01).

## Alternatives considered

| Alternative | Why rejected |
| --- | --- |
| `BigDecimal` everywhere | Exact, but slow and allocation-heavy at 1M lines, and carries scale ambiguity (`1.0` vs `1.00` compare unequal under `equals`). Fine at the boundary, wrong in the hot path |
| `double` | Not exact. Violates NFR-05 |
| `long` with an implicit global exponent of 2 | Breaks JPY and KWD |
| Strings compared lexically | Not arithmetic |

## Consequences

- Comparisons and sums are exact, fast and allocation-free.
- Display formatting (adding the decimal point) is the API layer's job.
- Anything that needs fractional arithmetic later (FX, interest) will need its own ADR; it must not leak into this type.
- Every parser must handle exponent correctly; this is why FRD-02 has explicit JPY and KWD acceptance criteria.

## Interview explanation

"Floating point can't represent decimal money exactly, so a reconciliation engine built on it can disagree with itself. I store signed minor units in a `long` with the currency, convert from text exactly at the boundary, reject rather than round, and enforce no-floating-point with an architecture test so it can't regress."
