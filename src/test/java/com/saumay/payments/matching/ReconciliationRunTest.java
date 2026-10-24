package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.matching.corpus.Corpus;
import com.saumay.payments.matching.corpus.CorpusGenerator;
import com.saumay.payments.matching.corpus.CorpusProfile;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** FRD-10: the engine is one pure function, so replay, shuffling and threads must all give the same hash. */
class ReconciliationRunTest {

    private static final MatchingConfig CONFIG = MatchingConfig.defaults();
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 1, 29);

    private static Corpus corpus() {
        return CorpusGenerator.generate(20261022L, 3000, CorpusProfile.standard());
    }

    private static RunResult run(Corpus corpus) {
        return ReconciliationRun.compute(corpus.statements(), corpus.ledger(), BUSINESS_DATE, CONFIG);
    }

    @Test
    void theOutputHashOfTheReferenceCorpusIsPinned() {
        // Computed once and pinned: any change to a score, tie rule or output field shows up here as a diff.
        // It must be identical on every JDK and machine (AC-10.5).
        assertEquals("b71a31ac469c93c5a68244ddd4305cfcabb3533842ecec7b71e3ca4648b67e84",
                run(corpus()).outputHash().hex());
    }

    @Test
    void shuffledInputsGiveTheSameCanonicalTextAndHash() {
        Corpus corpus = corpus();
        RunResult expected = run(corpus);
        SplittableRandom random = new SplittableRandom(11);
        for (int shuffle = 0; shuffle < 4; shuffle++) {
            RunResult actual = ReconciliationRun.compute(shuffled(corpus.statements(), random),
                    shuffled(corpus.ledger(), random), BUSINESS_DATE, CONFIG);
            assertEquals(expected.canonicalText(), actual.canonicalText(), "shuffle " + shuffle);
            assertEquals(expected.outputHash(), actual.outputHash());
        }
    }

    @Test
    void manyThreadsRunningTheEngineAtOnceAllGetTheSameHash() throws InterruptedException, ExecutionException {
        Corpus corpus = corpus();
        String expected = run(corpus).outputHash().hex();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> run(corpus).outputHash().hex()));
            }
            for (Future<String> future : futures) {
                assertEquals(expected, future.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void replayAgreesWithTheStoredHashAndRefusesAnyOtherResult() {
        Corpus corpus = corpus();
        String stored = run(corpus).outputHash().hex();

        assertTrue(ReconciliationRun.replayMatches(corpus.statements(), corpus.ledger(), BUSINESS_DATE, CONFIG,
                stored));
        assertFalse(ReconciliationRun.replayMatches(corpus.statements(), corpus.ledger(), BUSINESS_DATE, CONFIG,
                "0".repeat(64)));
    }

    @Test
    void replayWithADifferentConfigurationDoesNotMatch() {
        Corpus corpus = corpus();
        String stored = run(corpus).outputHash().hex();
        MatchingConfig narrower = new MatchingConfig(950, 700, 1, 10, 8, 600, 600);

        assertFalse(ReconciliationRun.replayMatches(corpus.statements(), corpus.ledger(), BUSINESS_DATE, narrower,
                stored));
    }

    @Test
    void aMissingInputLineChangesTheHashSoAnIncompleteReplayCannotPass() {
        Corpus corpus = corpus();
        List<MatchableLine> fewer = new ArrayList<>(corpus.ledger());
        fewer.remove(0);

        assertNotEquals(run(corpus).outputHash(),
                ReconciliationRun.compute(corpus.statements(), fewer, BUSINESS_DATE, CONFIG).outputHash());
    }

    @Test
    void theBusinessDateOnlyMattersThroughTheLookbackWindow() {
        Corpus corpus = corpus();
        RunResult later = ReconciliationRun.compute(corpus.statements(), corpus.ledger(),
                BUSINESS_DATE.plusDays(30), CONFIG);
        RunResult sameAgain = run(corpus);

        assertEquals(sameAgain.outputHash(), run(corpus).outputHash());
        assertEquals(sameAgain.pipeline(), later.pipeline());
        assertNotEquals(sameAgain.outputHash(), ReconciliationRun.compute(corpus.statements(), corpus.ledger(),
                LocalDate.of(2026, 10, 20), CONFIG).outputHash());
    }

    @Test
    void theSummaryCountsAddUpToTheOutput() {
        Corpus corpus = corpus();
        RunSummary summary = run(corpus).summary();

        assertEquals(corpus.statements().size(), summary.statementLines());
        assertEquals(corpus.ledger().size(), summary.ledgerEntries());
        assertEquals(summary.matches(), summary.autoConfirmedMatches() + summary.reviewMatches());
        assertTrue(summary.autoConfirmedMatches() > summary.reviewMatches());
        assertTrue(summary.exceptions() >= summary.reviewMatches() + summary.ambiguousGroups());
    }

    private static List<MatchableLine> shuffled(List<MatchableLine> lines, SplittableRandom random) {
        List<MatchableLine> copy = new ArrayList<>(lines);
        for (int i = copy.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            MatchableLine swap = copy.get(i);
            copy.set(i, copy.get(j));
            copy.set(j, swap);
        }
        return copy;
    }
}