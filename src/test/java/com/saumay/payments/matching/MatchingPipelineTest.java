package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.model.LineKey;
import com.saumay.payments.domain.model.LineSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class MatchingPipelineTest {

    private static final MatchingConfig CONFIG = MatchingConfig.defaults();
    private static final LocalDate FRIDAY = Lines.FRIDAY;

    @Test
    void eachLineIsClaimedByTheFirstTierThatCanTakeIt() {
        MatchableLine exactStatement = Lines.statement(0, "EX1", 1000, "GBP", FRIDAY);
        MatchableLine exactLedger = Lines.ledger(0, "EX1", 1000, "GBP", FRIDAY);
        MatchableLine toleranceStatement = Lines.statement(1, "TL1", 2000, "GBP", FRIDAY);
        MatchableLine toleranceLedger = Lines.ledger(1, "TL1", 2000, "GBP", FRIDAY.plusDays(3));
        MatchableLine fuzzyStatement = Lines.statement(2, "FZ1", 3000, "GBP", FRIDAY);
        MatchableLine fuzzyLedger = Lines.ledger(2, "FZ9", 3000, "GBP", FRIDAY);
        MatchableLine batchStatement = Lines.statement(3, "BT1", 9000, "GBP", FRIDAY);
        MatchableLine partOne = Lines.ledger(3, "P1", 5000, "GBP", FRIDAY);
        MatchableLine partTwo = Lines.ledger(4, "P2", 4000, "GBP", FRIDAY);
        MatchableLine lonely = Lines.statement(4, "LN1", 123, "GBP", FRIDAY);

        PipelineResult result = MatchingPipeline.run(
                List.of(lonely, batchStatement, fuzzyStatement, toleranceStatement, exactStatement),
                List.of(partTwo, fuzzyLedger, partOne, toleranceLedger, exactLedger), CONFIG);

        List<MatchTier> tiers = new ArrayList<>();
        for (Match match : result.matches()) {
            tiers.add(match.tier());
        }
        assertEquals(List.of(MatchTier.EXACT, MatchTier.TOLERANCE, MatchTier.FUZZY, MatchTier.ONE_TO_MANY), tiers);
        assertEquals(MatchBand.AUTO_CONFIRM, result.matches().get(0).band());
        assertEquals(MatchBand.AUTO_CONFIRM, result.matches().get(1).band());
        assertEquals(MatchBand.REVIEW, result.matches().get(2).band());
        assertEquals(MatchBand.REVIEW, result.matches().get(3).band());
        assertEquals(List.of(), result.ambiguous());
        assertEquals(List.of(lonely), result.unmatchedStatements());
        assertEquals(List.of(), result.unmatchedLedger());
        assertEquals(List.of(), result.notes());
    }

    @Test
    void aLineHeldBackAsAmbiguousIsNotOfferedToALaterTier() {
        MatchableLine first = Lines.statement(0, "TW1", 1000, "GBP", FRIDAY);
        MatchableLine second = Lines.statement(1, "TW1", 1000, "GBP", FRIDAY);
        MatchableLine entry = Lines.ledger(0, "TW1", 1000, "GBP", FRIDAY);

        PipelineResult result = MatchingPipeline.run(List.of(second, first), List.of(entry), CONFIG);

        assertEquals(List.of(), result.matches());
        assertEquals(1, result.ambiguous().size());
        assertEquals(MatchTier.EXACT, result.ambiguous().get(0).tier());
        assertEquals(List.of(), result.unmatchedStatements());
        assertEquals(List.of(), result.unmatchedLedger());
    }

    @Test
    void truncationNotesFromTierFourReachTheRunResult() {
        MatchableLine settlement = Lines.statement(0, "S1", 1000, "GBP", FRIDAY);
        List<MatchableLine> ledger = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ledger.add(Lines.ledger(i, "F" + i, 100 + i, "GBP", FRIDAY));
        }

        PipelineResult result = MatchingPipeline.run(List.of(settlement), ledger, CONFIG);

        assertEquals(1, result.notes().size());
        assertTrue(result.notes().get(0).startsWith(OneToManyTier.TRUNCATED_NOTE));
    }

    @Test
    void everyInputLineAppearsExactlyOnceInTheResult() {
        SplittableRandom random = new SplittableRandom(20261022L);
        long[] amounts = {500, 1000, 1500, 2000, 2500, 3000, 4000};
        for (int trial = 0; trial < 200; trial++) {
            List<MatchableLine> statements = world(random, true, amounts);
            List<MatchableLine> ledger = world(random, false, amounts);

            PipelineResult result = MatchingPipeline.run(statements, ledger, CONFIG);

            List<LineKey> seen = new ArrayList<>();
            for (Match match : result.matches()) {
                match.statementSide().forEach(line -> seen.add(line.key()));
                match.ledgerSide().forEach(line -> seen.add(line.key()));
            }
            for (AmbiguousGroup group : result.ambiguous()) {
                group.statementSide().forEach(line -> seen.add(line.key()));
                group.ledgerSide().forEach(line -> seen.add(line.key()));
            }
            result.unmatchedStatements().forEach(line -> seen.add(line.key()));
            result.unmatchedLedger().forEach(line -> seen.add(line.key()));

            Set<LineKey> expected = new TreeSet<>();
            statements.forEach(line -> expected.add(line.key()));
            ledger.forEach(line -> expected.add(line.key()));
            assertEquals(statements.size() + ledger.size(), seen.size(), "trial " + trial);
            assertEquals(expected, new TreeSet<>(seen), "trial " + trial);
        }
    }

    private static List<MatchableLine> world(SplittableRandom random, boolean statementSide, long[] amounts) {
        List<MatchableLine> lines = new ArrayList<>();
        for (int seq = 0; seq < 8; seq++) {
            String reference = "R" + random.nextInt(4);
            long amount = amounts[random.nextInt(amounts.length)];
            LocalDate date = FRIDAY.plusDays(random.nextInt(6));
            lines.add(Lines.build(statementSide ? LineSource.STATEMENT : LineSource.LEDGER, Lines.ACCOUNT, seq,
                    reference, amount, "GBP", date, "ACME TRADING", "INVOICE PAYMENT"));
        }
        return lines;
    }
}