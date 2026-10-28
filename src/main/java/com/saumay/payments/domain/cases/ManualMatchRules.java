package com.saumay.payments.domain.cases;

import com.saumay.payments.domain.cases.MatchLine.Side;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The rules a person's match must satisfy before it can resolve a case (AC-12.1, AC-12.2). A match is one line on
 * one side against one or more lines on the other, in a single currency, and the two sides must add up to exactly
 * the same number of minor units: a split is rejected rather than approximated.
 */
public final class ManualMatchRules {

    /** The same bound the matcher uses for one-to-many candidates by default. */
    public static final int MAX_COMPONENTS = 8;

    private ManualMatchRules() {
    }

    /** MANUAL_MATCH for one line against one line, SPLIT when one side has several lines. */
    public static ResolutionType classify(List<MatchLine> statements, List<MatchLine> ledger) {
        if (statements.isEmpty() || ledger.isEmpty()) {
            throw reject("A match needs at least one statement line and one ledger entry");
        }
        if (statements.size() > MAX_COMPONENTS || ledger.size() > MAX_COMPONENTS) {
            throw reject("A match can have at most " + MAX_COMPONENTS + " lines on one side");
        }
        if (statements.size() > 1 && ledger.size() > 1) {
            throw reject("One side of a match must be a single line; many-to-many matches are not supported");
        }
        requireSide(statements, Side.STATEMENT);
        requireSide(ledger, Side.LEDGER);
        String currency = statements.get(0).currency();
        requireCurrency(currency, statements);
        requireCurrency(currency, ledger);
        long statementTotal = total(statements);
        long ledgerTotal = total(ledger);
        if (statementTotal != ledgerTotal) {
            throw reject("The statement side (" + statementTotal + ") and the ledger side (" + ledgerTotal
                    + ") must add up to exactly the same amount in minor units");
        }
        return statements.size() == 1 && ledger.size() == 1 ? ResolutionType.MANUAL_MATCH : ResolutionType.SPLIT;
    }

    private static void requireSide(List<MatchLine> lines, Side side) {
        Set<Long> seen = new TreeSet<>();
        for (MatchLine line : lines) {
            if (line.side() != side) {
                throw reject("A line was supplied on the wrong side of the match");
            }
            if (!seen.add(line.seq())) {
                throw reject("The same line cannot appear twice in a match");
            }
        }
    }

    private static void requireCurrency(String currency, List<MatchLine> lines) {
        for (MatchLine line : lines) {
            if (!currency.equals(line.currency())) {
                throw reject("All lines in a match must be in the same currency");
            }
        }
    }

    private static long total(List<MatchLine> lines) {
        long sum = 0;
        for (MatchLine line : lines) {
            try {
                sum = Math.addExact(sum, line.amountMinor());
            } catch (ArithmeticException e) {
                throw reject("The amounts are too large to add up");
            }
        }
        return sum;
    }

    private static ManualMatchRejectedException reject(String message) {
        return new ManualMatchRejectedException(message);
    }
}