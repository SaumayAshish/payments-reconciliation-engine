package com.saumay.payments.bench;

import com.saumay.payments.matching.corpus.Corpus;
import com.saumay.payments.matching.corpus.CorpusGenerator;
import com.saumay.payments.matching.corpus.CorpusProfile;
import java.time.LocalDate;

/**
 * The benchmark datasets: the labelled synthetic corpus at a fixed seed and the standard profile, so every
 * machine measures the same input. The business date is after every value date the generator can produce.
 */
final class BenchData {

    static final long SEED = 20261022L;
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 10, 5);

    /** The corpus, the business date to run it at, and how long generating it took. */
    record Dataset(Corpus corpus, LocalDate businessDate, long generateMillis) {
    }

    private BenchData() {
    }

    static Dataset generate(int statementLines) {
        long start = System.nanoTime();
        Corpus corpus = CorpusGenerator.generate(SEED, statementLines, CorpusProfile.standard());
        long millis = (System.nanoTime() - start) / 1_000_000;
        int businessDays = Math.max(20, statementLines / 50);
        LocalDate businessDate = FIRST_DAY.plusDays((long) Math.ceil((businessDays + 10) * 7 / 5.0) + 30);
        return new Dataset(corpus, businessDate, millis);
    }
}