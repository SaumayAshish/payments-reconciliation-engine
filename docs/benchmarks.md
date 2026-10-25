# Benchmarks

Every number on this page was measured on one machine, on the dates and with the commands shown. Nothing is estimated or copied from elsewhere. Where a number is a single cold run, it says so.

## What is measured

The pure matching pipeline: `ReconciliationRun.compute` (all four tiers, resolution, classification, canonical output and the SHA-256 output hash) over the labelled synthetic corpus. The corpus is built in memory before timing starts. **Not measured:** database reads and writes, the HTTP layer, ingestion parsing. Those are separate costs and are not part of these figures.

## Environment

| Item | Value |
|---|---|
| CPU | AMD Ryzen 7 5700U, 8 cores / 16 logical processors (laptop part) |
| RAM | 13.8 GB |
| OS | Windows 11 amd64 |
| JDK | Oracle Java SE 26.0.2+10-55 (HotSpot 64-Bit Server VM). The project compiles to Java 25 bytecode (`release 25`); the benchmarks ran on JDK 26. |
| JVM options | `-Xmx8g` |
| Corpus | seed 20261022, generator version 1, `CorpusProfile.standard()` |
| Matching configuration | `MatchingConfig.defaults()` |
| Run conditions | TODO(author): confirm before publishing: charger plugged in, power mode, Docker/Edge/IntelliJ closed |

## Results: end-to-end runner (cold JVM)

Command: `java -Xmx8g -cp $cp com.saumay.payments.bench.BenchRunner <lines> <repeats>`

Each repeat recomputes the same run; every repeat produced the same output hash (the runner stops otherwise). The first repeat includes JIT warm-up.

| Statement lines | Ledger entries | Repeats (ms) | Median (ms) | Lines/sec (median) | Heap in use after |
|---|---|---|---|---|---|
| 10,002 | 9,970 | 456, 158, 117 | 158 | 126,405 | 73 MB |
| 100,001 | 99,660 | 1,619, 1,164, 864 | 1,164 | 171,530 | 1,097 MB |
| 1,000,000 | 996,938 | 12,798 (one cold run) | 12,798 | 156,035 | 2,934 MB |

Lines/sec = (statement lines + ledger entries) / median seconds.

Corpus generation time (not part of the run time): 439 ms at 10K, 2,897 ms at 100K, 190,431 ms at 1M.

Output hashes (first 16 hex characters):

| Statement lines | Output hash |
|---|---|
| 10,002 | 97ee7283dd46c8c3 |
| 100,001 | b7ef25d2cce040c6 |
| 1,000,000 | 9332f2a339426c09 |

Run outcome at each size (from the run summary):

| Statement lines | Matches | Auto-confirmed | Review | Ambiguous groups | Exceptions |
|---|---|---|---|---|---|
| 10,002 | 9,688 | 9,237 | 451 | 27 | 845 |
| 100,001 | 96,864 | 92,375 | 4,489 | 281 | 8,395 |
| 1,000,000 | 968,714 | 923,751 | 44,963 | 2,811 | 84,027 |

The auto-confirmed share of statement lines is 92.4% at every size.

## Results: JMH (steady state)

Command: `java -cp $cp org.openjdk.jmh.Main MatchingEngineBenchmark -rf json -rff target\jmh-result.json`

Configuration: average time per operation, 1 fork, 3 warm-up iterations of 5 s, 5 measurement iterations of 5 s, `-Xmx8g`, JMH 1.37.

| Statement lines | Score (ms/op) | Error (99.9%) | Min - max (ms) |
|---|---|---|---|
| 3,000 | 11.493 | ± 1.399 | 11.149 - 11.922 |
| 10,000 | 49.356 | ± 1.389 | 49.034 - 49.903 |
| 100,000 | 777.966 | ± 29.252 | 771.727 - 788.194 |

Derived throughput, steady state (statement lines + ledger entries per second): about 404,600 at 10K and about 256,600 at 100K.

Scaling: 10K to 100K is 10x the lines for 15.8x the time (roughly n^1.2). The growth is slightly super-linear; there is no quadratic cliff up to 1M lines. The cause has not been investigated (cache and GC pressure are the likely candidates).

## Determinism evidence

- Every repeat at every size produced an identical output hash, or the runner would have stopped.
- The 100K output hash is identical before and after the corpus generator fix described below: `b7ef25d2cce040c61524f59c071262cf8a7a89bb1c197e23e79c608ef704acdc`.
- The 3,000-line corpus hash is pinned in `ReconciliationRunTest`.

## Finding: corpus generator capacity

The first 1M-line attempt did not finish. A thread dump showed the generator looping forever in `CorpusGenerator.uniqueAmountNoReserve`: ordinary amounts must be unique and congruent to 1 mod 11, and the 2-to-6-digit range holds only about 91,000 such values, shared by all scenarios, so the generator could not produce more than roughly 130,000 lines. The engine was not involved. Fix (commit f681ceb): after 10,000 consecutive failed draws, widen the range by one digit. Below that threshold the random sequence is unchanged, so every corpus that could be built before is identical (shown by the unchanged 100K hash).

## Limitations

- One machine, one laptop-class CPU. Absolute numbers will differ elsewhere; use the scaling shape and the hashes, not the milliseconds, to compare.
- The 1M figure is one cold run. It has no warm-up and no repeats.
- Benchmarks ran on JDK 26.0.2 while the project targets Java 25.
- JMH used one fork; JDK 26 reported the experimental compiler Blackhole mode.
- Synthetic data with a fixed profile. Real data distributions will differ.
- Database-backed end-to-end timing (ingestion through the API, run persistence and paging) has not been measured yet.

## Reproduce

```text
mvn -Pbench clean test-compile dependency:build-classpath "-Dmdep.outputFile=target\bench.cp" "-Dmdep.includeScope=test"
$cp = "target\test-classes;target\classes;" + (Get-Content target\bench.cp -Raw).Trim()
java -Xmx8g -cp $cp com.saumay.payments.bench.BenchRunner 100000 3
java -cp $cp org.openjdk.jmh.Main MatchingEngineBenchmark
```

Run `mvn clean verify` afterwards to return to the strict build.