package com.saumay.payments.domain.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Property tests over a fixed-seed generator: failures are reproducible because the seed is constant. Amounts stay
 * within 2^40 so that sums of three cannot overflow; overflow itself is covered in MoneyTest.
 */
class MoneyPropertyTest {

    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final long BOUND = 1L << 40;
    private static final int CASES = 20_000;

    private static Money any(SplittableRandom random, CurrencyCode currency) {
        return Money.of(random.nextLong(-BOUND, BOUND + 1), currency);
    }

    @Test
    void additionIsCommutativeAndAssociative() {
        SplittableRandom random = new SplittableRandom(20261009L);
        for (int i = 0; i < CASES; i++) {
            Money a = any(random, GBP);
            Money b = any(random, GBP);
            Money c = any(random, GBP);
            assertEquals(a.plus(b), b.plus(a));
            assertEquals(a.plus(b).plus(c), a.plus(b.plus(c)));
        }
    }

    @Test
    void subtractingWhatWasAddedRestoresTheOriginal() {
        SplittableRandom random = new SplittableRandom(1L);
        for (int i = 0; i < CASES; i++) {
            Money a = any(random, GBP);
            Money b = any(random, GBP);
            assertEquals(a, a.plus(b).minus(b));
            assertEquals(Money.zero(GBP), a.plus(a.negate()));
        }
    }

    @Test
    void negateIsItsOwnInverseAndAbsIsNeverNegative() {
        SplittableRandom random = new SplittableRandom(2L);
        for (int i = 0; i < CASES; i++) {
            Money a = any(random, GBP);
            assertEquals(a, a.negate().negate());
            assertTrue(a.abs().signum() >= 0);
        }
    }

    @Test
    void comparisonIsConsistentWithSubtraction() {
        SplittableRandom random = new SplittableRandom(3L);
        for (int i = 0; i < CASES; i++) {
            Money a = any(random, GBP);
            Money b = any(random, GBP);
            assertEquals(Integer.signum(a.compareTo(b)), a.minus(b).signum());
            assertEquals(-Integer.signum(a.compareTo(b)), Integer.signum(b.compareTo(a)));
        }
    }

    @Test
    void mixingCurrenciesAlwaysFails() {
        SplittableRandom random = new SplittableRandom(4L);
        for (int i = 0; i < 1_000; i++) {
            Money gbp = any(random, GBP);
            Money usd = any(random, USD);
            assertThrows(CurrencyMismatchException.class, () -> gbp.plus(usd));
        }
    }
}