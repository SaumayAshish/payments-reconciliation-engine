package com.saumay.payments.matching.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.model.LineKey;
import com.saumay.payments.matching.MatchableLine;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The self-checks of docs/synthetic-data-spec.md section 7: the labels must be right before they judge anything. */
class CorpusGeneratorTest {

    private static final long SEED = 20261022L;
    private static final int LINES = 3000;

    private static Corpus corpus() {
        return CorpusGenerator.generate(SEED, LINES, CorpusProfile.standard());
    }

    @Test
    void theSameSeedGivesTheIdenticalCorpusAndADifferentSeedDoesNot() {
        assertEquals(corpus(), corpus());
        assertNotEquals(corpus(), CorpusGenerator.generate(SEED + 1, LINES, CorpusProfile.standard()));
    }

    @Test
    void everyLineBelongsToExactlyOneTruthCaseAndEveryKeyExists() {
        Corpus corpus = corpus();
        Set<LineKey> statementKeys = keys(corpus.statements());
        Set<LineKey> ledgerKeys = keys(corpus.ledger());
        assertEquals(corpus.statements().size(), statementKeys.size(), "statement keys are unique");
        assertEquals(corpus.ledger().size(), ledgerKeys.size(), "ledger keys are unique");

        Set<LineKey> claimed = new HashSet<>();
        for (TruthCase truth : corpus.cases()) {
            for (LineKey key : truth.statements()) {
                assertTrue(statementKeys.contains(key), "unknown statement key in case " + truth.id());
                assertTrue(claimed.add(key), "line in two cases: case " + truth.id());
            }
            for (LineKey key : truth.ledger()) {
                assertTrue(ledgerKeys.contains(key), "unknown ledger key in case " + truth.id());
                assertTrue(claimed.add(key), "line in two cases: case " + truth.id());
            }
        }
        assertEquals(statementKeys.size() + ledgerKeys.size(), claimed.size(), "no line without a label");
    }

    @Test
    void everyOneToManyCaseSumsExactlyAndSharesAccountCurrencyAndSign() {
        Corpus corpus = corpus();
        Map<LineKey, MatchableLine> byKey = new HashMap<>();
        corpus.statements().forEach(line -> byKey.put(line.key(), line));
        corpus.ledger().forEach(line -> byKey.put(line.key(), line));

        int checked = 0;
        for (TruthCase truth : corpus.cases()) {
            if (truth.scenario() != Scenario.ONE_TO_MANY) {
                continue;
            }
            boolean statementIsSingle = truth.statements().size() == 1;
            List<LineKey> many = statementIsSingle ? truth.ledger() : truth.statements();
            MatchableLine single = byKey.get(statementIsSingle ? truth.statements().get(0) : truth.ledger().get(0));
            assertTrue(many.size() >= 2 && many.size() <= 6, "2 to 6 components");
            long sum = 0;
            for (LineKey key : many) {
                MatchableLine part = byKey.get(key);
                sum += part.line().amount().minorUnits();
                assertEquals(single.line().account(), part.line().account());
                assertEquals(single.line().amount().currency(), part.line().amount().currency());
                assertEquals(single.line().amount().signum(), part.line().amount().signum());
            }
            assertEquals(single.line().amount().minorUnits(), sum, "case " + truth.id());
            checked++;
        }
        assertTrue(checked >= 4, "both directions are present");
        assertTrue(corpus.cases().stream().anyMatch(c -> c.scenario() == Scenario.ONE_TO_MANY
                && c.statements().size() > 1), "reverse direction present");
    }

    @Test
    void noAmountIsZeroAndNegativeAmountsAreOnTheCorpus() {
        Corpus corpus = corpus();
        boolean negative = false;
        for (MatchableLine line : corpus.statements()) {
            assertFalse(line.line().amount().isZero());
            negative |= line.line().amount().signum() < 0;
        }
        for (MatchableLine line : corpus.ledger()) {
            assertFalse(line.line().amount().isZero());
        }
        assertTrue(negative, "negative amounts must be exercised");
    }

    @Test
    void everyScenarioIsPresentAndTheBigOnesFollowTheProfileWithinRounding() {
        Corpus corpus = corpus();
        Map<Scenario, Integer> cases = new EnumMap<>(Scenario.class);
        for (TruthCase truth : corpus.cases()) {
            cases.merge(truth.scenario(), 1, Integer::sum);
        }
        for (Scenario scenario : Scenario.values()) {
            assertTrue(cases.getOrDefault(scenario, 0) > 0, scenario + " must be present");
        }
        CorpusProfile profile = CorpusProfile.standard();
        assertEquals(LINES * profile.tolerance1d() / 1000, cases.get(Scenario.TOLERANCE_1D));
        assertEquals(LINES * profile.fuzzy() / 1000, cases.get(Scenario.FUZZY));
        assertEquals(LINES * profile.noLedger() / 1000, cases.get(Scenario.NO_LEDGER));
        assertEquals(LINES * profile.noStatementPermille() / 1000, cases.get(Scenario.NO_STATEMENT));
        assertTrue(Math.abs(corpus.statements().size() - LINES) <= 20, "statement lines near the request");
    }

    @Test
    void identicalLinesExistOnlyWhereAScenarioPutsThemOnPurpose() {
        Corpus corpus = corpus();
        Map<LineKey, Scenario> scenarioOf = new HashMap<>();
        for (TruthCase truth : corpus.cases()) {
            truth.statements().forEach(key -> scenarioOf.put(key, truth.scenario()));
            truth.ledger().forEach(key -> scenarioOf.put(key, truth.scenario()));
        }
        for (List<MatchableLine> side : List.of(corpus.statements(), corpus.ledger())) {
            Map<String, List<MatchableLine>> bySignature = new HashMap<>();
            for (MatchableLine line : side) {
                bySignature.computeIfAbsent(line.line().account() + "|" + line.line().amount() + "|"
                                + line.line().valueDate() + "|" + line.line().referenceNorm(), k -> new ArrayList<>())
                        .add(line);
            }
            for (List<MatchableLine> twins : bySignature.values()) {
                if (twins.size() > 1) {
                    for (MatchableLine twin : twins) {
                        Scenario scenario = scenarioOf.get(twin.key());
                        assertTrue(scenario == Scenario.DUPLICATE_REFERENCE || scenario == Scenario.IDENTICAL_TWINS,
                                "unexpected duplicate in " + scenario);
                    }
                }
            }
        }
    }

    @Test
    void badInputIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CorpusGenerator.generate(SEED, 99, CorpusProfile.standard()));
        assertThrows(IllegalArgumentException.class,
                () -> new CorpusProfile("broken", 900, 100, 20, 20, 10, 15, 15, 10));
    }

    private static Set<LineKey> keys(List<MatchableLine> lines) {
        Set<LineKey> keys = new HashSet<>();
        lines.forEach(line -> keys.add(line.key()));
        return keys;
    }
}