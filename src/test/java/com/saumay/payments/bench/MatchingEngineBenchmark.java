package com.saumay.payments.bench;

import com.saumay.payments.matching.MatchingConfig;
import com.saumay.payments.matching.ReconciliationRun;
import com.saumay.payments.matching.corpus.Corpus;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * JMH benchmark of the whole pure matching pipeline (all four tiers, resolution, classification, canonical
 * output and hash) over the labelled synthetic corpus. Corpus generation is in setup, not in the measurement.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@Fork(value = 1, jvmArgsAppend = "-Xmx8g")
@State(Scope.Benchmark)
public class MatchingEngineBenchmark {

    @Param({"3000", "10000", "100000"})
    public int statementLines;

    private Corpus corpus;
    private LocalDate businessDate;
    private MatchingConfig config;

    @Setup(Level.Trial)
    public void generate() {
        BenchData.Dataset data = BenchData.generate(statementLines);
        corpus = data.corpus();
        businessDate = data.businessDate();
        config = MatchingConfig.defaults();
    }

    @Benchmark
    public Object fullRun() {
        return ReconciliationRun.compute(corpus.statements(), corpus.ledger(), businessDate, config);
    }
}