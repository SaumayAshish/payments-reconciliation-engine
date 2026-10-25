package com.saumay.payments.bench;

import com.saumay.payments.matching.MatchingConfig;
import com.saumay.payments.matching.ReconciliationRun;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * End-to-end timing of one reconciliation run over a synthetic corpus. Usage: BenchRunner SIZE [REPEATS].
 * Every repeat must produce the same output hash, otherwise the run is not deterministic and the numbers are
 * worthless, so the runner stops. Generation time is reported separately and is not part of the run time.
 */
public final class BenchRunner {

    private BenchRunner() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: BenchRunner STATEMENT_LINES [REPEATS]");
            return;
        }
        int size = Integer.parseInt(args[0]);
        int repeats = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        MatchingConfig config = MatchingConfig.defaults();

        BenchData.Dataset data = BenchData.generate(size);
        int statements = data.corpus().statements().size();
        int ledger = data.corpus().ledger().size();

        List<Long> millis = new ArrayList<>();
        Object hash = null;
        String summary = "";
        for (int i = 0; i < repeats; i++) {
            long start = System.nanoTime();
            var result = ReconciliationRun.compute(data.corpus().statements(), data.corpus().ledger(),
                    data.businessDate(), config);
            millis.add((System.nanoTime() - start) / 1_000_000);
            if (hash == null) {
                hash = result.outputHash();
                summary = String.valueOf(result.summary());
            } else if (!hash.equals(result.outputHash())) {
                throw new IllegalStateException("Non-deterministic output: " + hash + " then " + result.outputHash());
            }
        }

        List<Long> sorted = new ArrayList<>(millis);
        Collections.sort(sorted);
        long median = sorted.get(sorted.size() / 2);
        long min = sorted.get(0);
        long lines = (long) statements + ledger;
        Runtime runtime = Runtime.getRuntime();

        System.out.println();
        System.out.println("=== BENCH RESULT (paste into docs/benchmarks.md) ===");
        System.out.println("java.version      " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vm.name") + ")");
        System.out.println("os                " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("logical cpus      " + runtime.availableProcessors());
        System.out.println("max heap MB       " + runtime.maxMemory() / (1024 * 1024));
        System.out.println("seed              " + BenchData.SEED);
        System.out.println("requested lines   " + size);
        System.out.println("statement lines   " + statements);
        System.out.println("ledger entries    " + ledger);
        System.out.println("business date     " + data.businessDate());
        System.out.println("generate ms       " + data.generateMillis());
        System.out.println("repeats           " + repeats);
        System.out.println("run ms (each)     " + millis);
        System.out.println("run ms (median)   " + median);
        System.out.println("run ms (min)      " + min);
        System.out.println("lines/sec median  " + (median == 0 ? 0 : lines * 1000 / median)
                + "  (statement lines + ledger entries per second)");
        System.out.println("output hash       " + String.valueOf(hash));
        System.out.println("summary           " + summary);
        System.out.println("used heap MB      " + (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        System.out.println("=== END ===");
    }
}