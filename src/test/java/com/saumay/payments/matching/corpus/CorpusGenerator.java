package com.saumay.payments.matching.corpus;

import com.saumay.payments.domain.model.CanonicalLine;
import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineKey;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.domain.money.CurrencyCode;
import com.saumay.payments.domain.money.Money;
import com.saumay.payments.ingestion.normalise.CounterpartyNormaliser;
import com.saumay.payments.ingestion.normalise.ReferenceNormaliser;
import com.saumay.payments.matching.MatchableLine;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.IntConsumer;

/**
 * Seeded generator for the labelled synthetic corpus (docs/synthetic-data-spec.md). Ground truth is decided
 * first and noise applied second, so every label is correct by construction. The generator uses a
 * {@link SplittableRandom} with the given seed and integer arithmetic only: the same (seed, size, profile)
 * gives the same lines and the same labels on every run. It never reads a clock and never touches the engine.
 *
 * <p>Every non-deliberate amount is unique across the corpus, so a line can only have the counterpart the
 * generator gave it, or the look-alike a hard-negative scenario put there on purpose.
 */
public final class CorpusGenerator {

    public static final String VERSION = "1";

    private record Account(String id, String currency) {
    }

    private static final List<Account> ACCOUNTS = List.of(
            new Account("NOSTRO-GBP-001", "GBP"), new Account("NOSTRO-GBP-002", "GBP"),
            new Account("NOSTRO-EUR-001", "EUR"), new Account("NOSTRO-USD-001", "USD"),
            new Account("NOSTRO-JPY-001", "JPY"), new Account("NOSTRO-KWD-001", "KWD"),
            new Account("NOSTRO-INR-001", "INR"));

    private static final List<Account> BATCH_ACCOUNTS = List.of(
            new Account("NOSTRO-GBP-901", "GBP"), new Account("NOSTRO-GBP-902", "GBP"),
            new Account("NOSTRO-EUR-901", "EUR"), new Account("NOSTRO-EUR-902", "EUR"),
            new Account("NOSTRO-USD-901", "USD"), new Account("NOSTRO-USD-902", "USD"));

    /** Batches in one isolated account are at least this many calendar days apart, beyond any tier 4 window. */
    private static final int BATCH_SPACING_DAYS = 14;

    /** Invented counterparties; none is a real company. */
    private static final List<String> COUNTERPARTIES = List.of("Northwind Trading", "Harbor Freight Supplies",
            "Contoso Metals", "Fabrikam Logistics", "Tailspin Aviation", "Litware Systems", "Adatum Energy",
            "Proseware Retail", "Wingtip Foods", "Lucerne Publishing", "Alpine Ski House", "Coho Vineyard");

    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 10, 5);
    private static final int MIN_BUSINESS_DAYS = 20;
    private static final int LINES_PER_BUSINESS_DAY = 50;
    private static final int HARD_NEGATIVE_KINDS = 12;

    /**
     * Every ordinary amount is 1 modulo 11, so a sum of j ordinary amounts is j modulo 11. A tier 4 search looks at
     * at most 12 lines, so no ordinary amount can ever be the sum of two or more other ordinary amounts by
     * accident; only the decompositions the generator builds on purpose exist.
     */
    private static final int ORDINARY_MODULUS = 11;
    private static final int ORDINARY_RESIDUE = 1;

    /**
     * An amount range of d digits holds only about 0.09 * 10^d values, and ordinary lines share them. When a
     * range is used up, the search widens by one digit after this many failed draws (up to MAX_AMOUNT_DIGITS).
     * Below that point the draw sequence is untouched, so every corpus that could be built before is unchanged.
     */
    private static final int EXHAUSTED_AFTER_FAILURES = 10_000;
    private static final int MAX_AMOUNT_DIGITS = 12;

    /** One true transaction before it is turned into lines. */
    private record Txn(Account account, long amount, LocalDate date, String reference, String counterparty) {

        String narrative() {
            return "PAYMENT FROM " + counterparty.toUpperCase(Locale.ROOT) + " " + reference;
        }
    }

    private final SplittableRandom random;
    private final ContentHash statementFile;
    private final ContentHash ledgerFile;
    private final List<MatchableLine> statements = new ArrayList<>();
    private final List<MatchableLine> ledger = new ArrayList<>();
    private final List<TruthCase> cases = new ArrayList<>();
    private final Set<Long> usedAmounts = new HashSet<>();
    private final Layout layout;
    private final int businessDays;
    private final Map<String, List<LocalDate>> batchDates = new HashMap<>();
    private int referenceCounter;

    private CorpusGenerator(long seed, Layout layout, int businessDays) {
        this.layout = layout;
        this.businessDays = businessDays;
        this.random = new SplittableRandom(seed);
        this.statementFile = ContentHash.ofUtf8("synthetic-statement-" + VERSION + "-" + seed);
        this.ledgerFile = ContentHash.ofUtf8("synthetic-ledger-" + VERSION + "-" + seed);
    }

    /** Where the batch scenarios (tier 4) are placed. */
    public enum Layout {
        /**
         * Each batch gets its own account and a date at least two weeks from any other batch there, so the
         * bounded search sees exactly the lines of that batch. Ground truth is exact by construction.
         */
        ISOLATED_BATCHES,
        /** Batches share accounts and dates with everything else, as in dense real data. Used to measure recall. */
        CROWDED
    }

    /**
     * Value dates are spread over 20 business days, or more for a large corpus so the number of lines per account
     * and day stays constant.
     */
    public static Corpus generate(long seed, int statementLines, CorpusProfile profile) {
        return generate(seed, statementLines, profile, Layout.ISOLATED_BATCHES,
                Math.max(MIN_BUSINESS_DAYS, statementLines / LINES_PER_BUSINESS_DAY));
    }

    public static Corpus generate(long seed, int statementLines, CorpusProfile profile, Layout layout,
                                  int businessDays) {
        if (statementLines < 100) {
            throw new IllegalArgumentException("A corpus needs at least 100 statement lines");
        }
        if (businessDays < 5) {
            throw new IllegalArgumentException("Dates must spread over at least 5 business days");
        }
        CorpusGenerator generator = new CorpusGenerator(seed, layout, businessDays);
        generator.build(statementLines, profile);
        return new Corpus(VERSION, seed, statementLines, profile, generator.statements, generator.ledger,
                generator.cases);
    }

    private static int budget(int statementLines, int permille) {
        return (permille * statementLines + 500) / 1000;
    }

    private void build(int total, CorpusProfile profile) {
        fill(budget(total, profile.exact()), 0, n -> exact());
        fill(budget(total, profile.tolerance1d()), 0, n -> tolerance(Scenario.TOLERANCE_1D, 1));
        fill(budget(total, profile.tolerance2d3d()), 0,
                n -> tolerance(Scenario.TOLERANCE_2D_3D, 2 + random.nextInt(2)));
        fill(budget(total, profile.fuzzy()), 0, n -> fuzzy());
        fill(budget(total, profile.oneToMany()), 4, n -> oneToMany(n % 2 == 0));
        fill(budget(total, profile.noLedger()), 0, n -> noLedger());
        fill(budget(total, profile.hardNegative()), HARD_NEGATIVE_KINDS, n -> hardNegative(n % HARD_NEGATIVE_KINDS));
        for (int i = 0; i < budget(total, profile.noStatementPermille()); i++) {
            noStatement();
        }
    }

    /**
     * Generates cases until the scenario has used its statement-line budget, and at least {@code minimumCases}
     * so that rare scenarios are always present even in a small corpus.
     */
    private void fill(int budget, int minimumCases, IntConsumer generator) {
        int start = statements.size();
        int made = 0;
        while (statements.size() < start + budget || made < minimumCases) {
            generator.accept(made);
            made++;
        }
    }

    // ---------------------------------------------------------------- scenarios

    private void exact() {
        Txn txn = newTxn();
        MatchableLine s = statement(txn);
        MatchableLine l = ledger(txn);
        matchCase(Scenario.EXACT, Expected.AUTO_EXACT, s, l);
    }

    private void tolerance(Scenario scenario, int shift) {
        Txn txn = newTxn();
        boolean ledgerLater = random.nextBoolean();
        LocalDate later = addBusinessDays(txn.date(), shift);
        MatchableLine s = statement(ledgerLater ? txn : withDate(txn, later));
        MatchableLine l = ledger(ledgerLater ? withDate(txn, later) : txn);
        Expected expected = shift == 1 ? Expected.AUTO_TOLERANCE : Expected.REVIEW_TOLERANCE;
        matchCase(scenario, expected, s, l);
    }

    private void fuzzy() {
        Txn txn = newTxn();
        MatchableLine s = statement(txn);
        MatchableLine l = fuzzyCounterpart(txn, random.nextInt(3));
        matchCase(Scenario.FUZZY, Expected.REVIEW_FUZZY, s, l);
    }

    /** Different reference, with one of three realistic differences in counterparty and narrative. */
    private MatchableLine fuzzyCounterpart(Txn txn, int variant) {
        String newReference = nextReference();
        LocalDate date = random.nextBoolean() ? txn.date() : addBusinessDays(txn.date(), 1);
        String upper = txn.counterparty().toUpperCase(Locale.ROOT);
        return switch (variant) {
            case 0 -> line(LineSource.LEDGER, txn.account(), txn.amount(), date, newReference,
                    txn.counterparty().toLowerCase(Locale.ROOT) + " Ltd.",
                    "PMT FROM " + upper + " " + newReference);
            case 1 -> line(LineSource.LEDGER, txn.account(), txn.amount(), date, newReference,
                    upper + " LIMITED", typo("PAYMENT FROM " + upper + " " + newReference));
            default -> line(LineSource.LEDGER, txn.account(), txn.amount(), date, newReference,
                    txn.counterparty() + " Group", "PAYMENT FROM " + upper + " GROUP " + newReference);
        };
    }

    private String typo(String text) {
        int at = text.length() / 2;
        char replacement = text.charAt(at) == 'Q' ? 'Z' : 'Q';
        return text.substring(0, at) + replacement + text.substring(at + 1);
    }

    private record Slot(Account account, LocalDate date) {
    }

    /** An account and value date for one batch: private in the isolated layout, anywhere in the crowded one. */
    private Slot batchSlot() {
        if (layout == Layout.CROWDED) {
            return new Slot(ACCOUNTS.get(random.nextInt(ACCOUNTS.size())), randomBusinessDay());
        }
        for (int attempt = 0; attempt < 10_000; attempt++) {
            Account account = BATCH_ACCOUNTS.get(random.nextInt(BATCH_ACCOUNTS.size()));
            LocalDate date = randomBusinessDay();
            List<LocalDate> used = batchDates.computeIfAbsent(account.id(), k -> new ArrayList<>());
            boolean clear = true;
            for (LocalDate other : used) {
                clear &= Math.abs(ChronoUnit.DAYS.between(other, date)) >= BATCH_SPACING_DAYS;
            }
            if (clear) {
                used.add(date);
                return new Slot(account, date);
            }
        }
        throw new IllegalStateException("No free batch slot: spread the dates over more business days");
    }

    private void oneToMany(boolean statementIsSingle) {
        Slot slot = batchSlot();
        Account account = slot.account();
        int parts = 2 + random.nextInt(5);
        long[] amounts = new long[parts];
        long total = 0;
        for (int i = 0; i < parts; i++) {
            amounts[i] = uniqueAmount(2, 6);
            total += amounts[i];
        }
        while (!usedAmounts.add(total)) {
            amounts[0] = uniqueAmount(2, 6);
            total = 0;
            for (long amount : amounts) {
                total += amount;
            }
        }
        int sign = random.nextInt(10) == 0 ? -1 : 1;
        LocalDate date = slot.date();
        String counterparty = COUNTERPARTIES.get(random.nextInt(COUNTERPARTIES.size()));
        String batchReference = nextReference();
        boolean sharedReference = random.nextBoolean();
        boolean sameDate = random.nextBoolean();

        Txn single = new Txn(account, sign * total, date, batchReference, counterparty);
        MatchableLine singleLine = statementIsSingle ? statement(single) : ledger(single);
        List<MatchableLine> components = new ArrayList<>();
        for (int i = 0; i < parts; i++) {
            LocalDate componentDate = sameDate ? date : addBusinessDays(date, random.nextInt(3));
            Txn part = new Txn(account, sign * amounts[i], componentDate,
                    sharedReference ? batchReference : nextReference(), counterparty);
            components.add(statementIsSingle ? ledger(part) : statement(part));
        }
        if (statementIsSingle) {
            matchCase(Scenario.ONE_TO_MANY, Expected.REVIEW_ONE_TO_MANY, List.of(singleLine), components);
        } else {
            matchCase(Scenario.ONE_TO_MANY, Expected.REVIEW_ONE_TO_MANY, components, List.of(singleLine));
        }
    }

    private void noLedger() {
        MatchableLine s = statement(newTxn());
        unmatchedCase(Scenario.NO_LEDGER, List.of(s), List.of());
    }

    private void noStatement() {
        MatchableLine l = ledger(newTxn());
        unmatchedCase(Scenario.NO_STATEMENT, List.of(), List.of(l));
    }

    private void hardNegative(int kind) {
        switch (kind) {
            case 0 -> sameAmountDifferentReference();
            case 1 -> sameReferenceDifferentAmount();
            case 2 -> sameReferenceDifferentCurrency();
            case 3 -> outOfWindow();
            case 4 -> reversalPair();
            case 5 -> reversalBothSides();
            case 6 -> duplicateReference();
            case 7 -> identicalTwins();
            case 8 -> fuzzyNearMiss();
            case 9 -> subsetAmbiguity();
            case 10 -> crossAccount();
            default -> weekendDate();
        }
    }

    /** A true exact pair, plus a third line with the same amount and date but nothing else in common. */
    private void sameAmountDifferentReference() {
        Txn txn = newTxn();
        MatchableLine s = statement(txn);
        MatchableLine l = ledger(txn);
        matchCase(Scenario.SAME_AMOUNT_DIFFERENT_REF, Expected.AUTO_EXACT, s, l);
        Txn decoy = new Txn(txn.account(), txn.amount(), txn.date(), nextReference(), otherCounterparty(txn));
        unmatchedCase(Scenario.SAME_AMOUNT_DIFFERENT_REF, List.of(statement(decoy)), List.of());
    }

    private void sameReferenceDifferentAmount() {
        Txn txn = newTxn();
        reserve(txn.amount() + 1);
        MatchableLine s = statement(txn);
        MatchableLine l = ledger(new Txn(txn.account(), txn.amount() + 1, txn.date(), txn.reference(),
                txn.counterparty()));
        unmatchedCase(Scenario.SAME_REF_DIFFERENT_AMOUNT, List.of(s), List.of(l));
    }

    private void sameReferenceDifferentCurrency() {
        Account other;
        Txn txn = newTxn();
        do {
            other = ACCOUNTS.get(random.nextInt(ACCOUNTS.size()));
        } while (other.currency().equals(txn.account().currency()));
        MatchableLine s = statement(txn);
        MatchableLine l = ledger(new Txn(other, txn.amount(), txn.date(), txn.reference(), txn.counterparty()));
        unmatchedCase(Scenario.SAME_REF_DIFFERENT_CURRENCY, List.of(s), List.of(l));
    }

    private void outOfWindow() {
        Txn txn = newTxn();
        MatchableLine s = statement(txn);
        MatchableLine l = ledger(withDate(txn, addBusinessDays(txn.date(), 4 + random.nextInt(3))));
        unmatchedCase(Scenario.OUT_OF_WINDOW, List.of(s), List.of(l));
    }

    /** A payment on the statement and its reversal in the ledger, same reference: never matched together. */
    private void reversalPair() {
        Txn txn = newTxn();
        MatchableLine s = statement(txn);
        MatchableLine l = ledger(new Txn(txn.account(), -txn.amount(), txn.date(), txn.reference(),
                txn.counterparty()));
        unmatchedCase(Scenario.REVERSAL_PAIR, List.of(s), List.of(l));
    }

    /** Payment and reversal both present on both sides: each pairs with its own sign and never across. */
    private void reversalBothSides() {
        Txn payment = newTxn();
        Txn reversal = new Txn(payment.account(), -payment.amount(), payment.date(), payment.reference(),
                payment.counterparty());
        MatchableLine s1 = statement(payment);
        MatchableLine s2 = statement(reversal);
        MatchableLine l1 = ledger(payment);
        MatchableLine l2 = ledger(reversal);
        matchCase(Scenario.REVERSAL_BOTH_SIDES, Expected.AUTO_EXACT, s1, l1);
        matchCase(Scenario.REVERSAL_BOTH_SIDES, Expected.AUTO_EXACT, s2, l2);
    }

    /** The same reference twice on the statement and once in the ledger: a tie, so nothing is confirmed. */
    private void duplicateReference() {
        Txn txn = newTxn();
        MatchableLine s1 = statement(txn);
        MatchableLine s2 = statement(txn);
        MatchableLine l = ledger(txn);
        ambiguousCase(Scenario.DUPLICATE_REFERENCE, List.of(s1, s2), List.of(l));
    }

    private void identicalTwins() {
        Txn txn = newTxn();
        ambiguousCase(Scenario.IDENTICAL_TWINS, List.of(statement(txn), statement(txn)),
                List.of(ledger(txn), ledger(txn)));
    }

    /** A fuzzy true pair, plus a ledger entry for a look-alike customer with the same amount and date. */
    private void fuzzyNearMiss() {
        Txn txn = newTxn();
        MatchableLine s = statement(txn);
        MatchableLine l = fuzzyCounterpart(txn, 0);
        matchCase(Scenario.FUZZY_NEAR_MISS, Expected.REVIEW_FUZZY, s, l);
        String lookAlike = txn.counterparty().split(" ")[0] + " Traders";
        MatchableLine decoy = line(LineSource.LEDGER, txn.account(), txn.amount(), txn.date(), nextReference(),
                lookAlike, "PAYMENT FROM " + lookAlike.toUpperCase(Locale.ROOT));
        unmatchedCase(Scenario.FUZZY_NEAR_MISS, List.of(), List.of(decoy));
    }

    /** Two different subsets of ledger entries sum to the statement amount: a human must choose. */
    private void subsetAmbiguity() {
        Slot slot = batchSlot();
        Account account = slot.account();
        long tenth;
        long total;
        do {
            tenth = uniqueAmountNoReserve(3, 5);
            total = 10 * tenth;
        } while (!usedAmounts.add(total) || !reserveAll(3 * tenth, 4 * tenth, 6 * tenth, 7 * tenth));
        LocalDate date = slot.date();
        String counterparty = COUNTERPARTIES.get(random.nextInt(COUNTERPARTIES.size()));
        MatchableLine s = statement(new Txn(account, total, date, nextReference(), counterparty));
        List<MatchableLine> entries = new ArrayList<>();
        for (long part : new long[] {3 * tenth, 7 * tenth, 4 * tenth, 6 * tenth}) {
            entries.add(ledger(new Txn(account, part, date, nextReference(), counterparty)));
        }
        ambiguousCase(Scenario.SUBSET_AMBIGUITY, List.of(s), entries);
    }

    private void crossAccount() {
        Account first = ACCOUNTS.get(0);
        Account second = ACCOUNTS.get(1);
        Txn txn = newTxn(first);
        MatchableLine s = statement(txn);
        MatchableLine l = ledger(new Txn(second, txn.amount(), txn.date(), txn.reference(), txn.counterparty()));
        unmatchedCase(Scenario.CROSS_ACCOUNT, List.of(s), List.of(l));
    }

    /** A weekend value date and the following Monday are zero business days apart (EC-04.6): tier 2, auto. */
    private void weekendDate() {
        Txn txn = newTxn();
        LocalDate saturday = randomBusinessDay().with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        if (saturday.isBefore(FIRST_DAY)) {
            saturday = saturday.plusWeeks(1);
        }
        MatchableLine s = statement(withDate(txn, saturday));
        MatchableLine l = ledger(withDate(txn, saturday.plusDays(2)));
        matchCase(Scenario.WEEKEND_DATE, Expected.AUTO_TOLERANCE, s, l);
    }

    // ---------------------------------------------------------------- building blocks

    private Txn newTxn() {
        return newTxn(ACCOUNTS.get(random.nextInt(ACCOUNTS.size())));
    }

    private Txn newTxn(Account account) {
        long magnitude = uniqueAmount(2, 8);
        long amount = random.nextInt(10) == 0 ? -magnitude : magnitude;
        return new Txn(account, amount, randomBusinessDay(), nextReference(),
                COUNTERPARTIES.get(random.nextInt(COUNTERPARTIES.size())));
    }

    private static Txn withDate(Txn txn, LocalDate date) {
        return new Txn(txn.account(), txn.amount(), date, txn.reference(), txn.counterparty());
    }

    private String otherCounterparty(Txn txn) {
        String other;
        do {
            other = COUNTERPARTIES.get(random.nextInt(COUNTERPARTIES.size()));
        } while (other.equals(txn.counterparty()));
        return other;
    }

    /** A new positive amount of {@code minDigits} to {@code maxDigits} digits, never used before. */
    private long uniqueAmount(int minDigits, int maxDigits) {
        long amount = uniqueAmountNoReserve(minDigits, maxDigits);
        usedAmounts.add(amount);
        return amount;
    }

    private long uniqueAmountNoReserve(int minDigits, int maxDigits) {
        int widest = maxDigits;
        int failures = 0;
        while (true) {
            int digits = minDigits + random.nextInt(widest - minDigits + 1);
            long low = pow10(digits - 1);
            long amount = low + random.nextLong(9 * low);
            amount = amount - amount % ORDINARY_MODULUS + ORDINARY_RESIDUE;
            if (amount < low) {
                amount += ORDINARY_MODULUS;
            }
            if (!usedAmounts.contains(amount)) {
                return amount;
            }
            failures++;
            if (failures % EXHAUSTED_AFTER_FAILURES == 0 && widest < MAX_AMOUNT_DIGITS) {
                widest++;
            }
        }
    }

    private void reserve(long amount) {
        usedAmounts.add(amount);
    }

    private boolean reserveAll(long... amounts) {
        for (long amount : amounts) {
            if (usedAmounts.contains(amount)) {
                return false;
            }
        }
        for (long amount : amounts) {
            usedAmounts.add(amount);
        }
        return true;
    }

    private static long pow10(int exponent) {
        long result = 1;
        for (int i = 0; i < exponent; i++) {
            result *= 10;
        }
        return result;
    }

    private String nextReference() {
        int n = referenceCounter++;
        return switch (n % 3) {
            case 0 -> String.format(Locale.ROOT, "INV-2026-%06d", n);
            case 1 -> String.format(Locale.ROOT, "PAY/%04d/XX", n);
            default -> String.format(Locale.ROOT, "SWF%08d", n);
        };
    }

    private LocalDate randomBusinessDay() {
        return addBusinessDays(FIRST_DAY, random.nextInt(businessDays));
    }

    private static LocalDate addBusinessDays(LocalDate from, int days) {
        LocalDate date = from;
        int remaining = days;
        while (remaining > 0) {
            date = date.plusDays(1);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                remaining--;
            }
        }
        return date;
    }

    private MatchableLine statement(Txn txn) {
        return line(LineSource.STATEMENT, txn.account(), txn.amount(), txn.date(), txn.reference(),
                txn.counterparty(), txn.narrative());
    }

    private MatchableLine ledger(Txn txn) {
        return line(LineSource.LEDGER, txn.account(), txn.amount(), txn.date(), txn.reference(),
                txn.counterparty(), txn.narrative());
    }

    private MatchableLine line(LineSource source, Account account, long amount, LocalDate date, String reference,
                               String counterparty, String narrative) {
        List<MatchableLine> side = source == LineSource.STATEMENT ? statements : ledger;
        ContentHash file = source == LineSource.STATEMENT ? statementFile : ledgerFile;
        int seq = side.size();
        CanonicalLine canonical = new CanonicalLine(source, account.id(),
                Money.of(amount, CurrencyCode.of(account.currency())), date, null, reference,
                ReferenceNormaliser.normalise(reference), counterparty,
                CounterpartyNormaliser.normalise(counterparty), narrative, LineKey.of(file, seq + 1));
        MatchableLine created = new MatchableLine(seq, canonical);
        side.add(created);
        return created;
    }

    // ---------------------------------------------------------------- truth

    private void matchCase(Scenario scenario, Expected expected, MatchableLine statement, MatchableLine entry) {
        matchCase(scenario, expected, List.of(statement), List.of(entry));
    }

    private void matchCase(Scenario scenario, Expected expected, List<MatchableLine> statementLines,
                           List<MatchableLine> ledgerLines) {
        cases.add(new TruthCase(cases.size(), scenario, expected, keys(statementLines), keys(ledgerLines)));
    }

    private void unmatchedCase(Scenario scenario, List<MatchableLine> statementLines,
                               List<MatchableLine> ledgerLines) {
        cases.add(new TruthCase(cases.size(), scenario, Expected.UNMATCHED, keys(statementLines),
                keys(ledgerLines)));
    }

    private void ambiguousCase(Scenario scenario, List<MatchableLine> statementLines,
                               List<MatchableLine> ledgerLines) {
        cases.add(new TruthCase(cases.size(), scenario, Expected.AMBIGUOUS, keys(statementLines),
                keys(ledgerLines)));
    }

    private static List<LineKey> keys(List<MatchableLine> lines) {
        List<LineKey> keys = new ArrayList<>();
        for (MatchableLine line : lines) {
            keys.add(line.key());
        }
        return keys;
    }
}