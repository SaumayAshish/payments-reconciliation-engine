package com.saumay.payments.domain.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApprovalThresholdTest {

    private final ApprovalThreshold defaults = ApprovalThreshold.defaults();

    @Test
    void anAmountEqualToTheThresholdDoesNotNeedApproval() {
        assertFalse(defaults.requiresApproval("GBP", ApprovalThreshold.DEFAULT_MINOR));
    }

    @Test
    void oneMinorUnitAboveTheThresholdNeedsApproval() {
        assertTrue(defaults.requiresApproval("GBP", ApprovalThreshold.DEFAULT_MINOR + 1));
    }

    @Test
    void theComparisonIsOnTheAbsoluteAmount() {
        assertFalse(defaults.requiresApproval("GBP", -ApprovalThreshold.DEFAULT_MINOR));
        assertTrue(defaults.requiresApproval("GBP", -ApprovalThreshold.DEFAULT_MINOR - 1));
    }

    @Test
    void theSmallestLongDoesNotOverflowAndNeedsApproval() {
        assertTrue(defaults.requiresApproval("GBP", Long.MIN_VALUE));
        assertTrue(new ApprovalThreshold(0, Map.of()).requiresApproval("GBP", Long.MIN_VALUE));
    }

    @Test
    void aCurrencyOverrideReplacesTheDefaultForThatCurrencyOnly() {
        ApprovalThreshold threshold = new ApprovalThreshold(1_000_000, Map.of("JPY", 100_000L));
        assertTrue(threshold.requiresApproval("JPY", 100_001));
        assertFalse(threshold.requiresApproval("GBP", 100_001));
        assertEquals(100_000, threshold.thresholdFor("JPY"));
        assertEquals(1_000_000, threshold.thresholdFor("EUR"));
    }

    @Test
    void aZeroThresholdMakesEveryNonZeroAmountControlled() {
        ApprovalThreshold threshold = new ApprovalThreshold(0, Map.of());
        assertTrue(threshold.requiresApproval("GBP", 1));
        assertFalse(threshold.requiresApproval("GBP", 0));
    }

    @Test
    void theOverrideMapIsCopiedSoLaterChangesToTheSourceDoNotLeakIn() {
        Map<String, Long> source = new HashMap<>();
        source.put("JPY", 5L);
        ApprovalThreshold threshold = new ApprovalThreshold(10, source);
        source.put("JPY", 999L);
        assertEquals(5, threshold.thresholdFor("JPY"));
        assertThrows(UnsupportedOperationException.class, () -> threshold.perCurrencyMinor().put("USD", 1L));
    }

    @Test
    void negativeThresholdsAndBadCurrenciesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ApprovalThreshold(-1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ApprovalThreshold(1, Map.of("GBP", -1L)));
        assertThrows(IllegalArgumentException.class, () -> new ApprovalThreshold(1, Map.of("gbp", 1L)));
        assertThrows(IllegalArgumentException.class, () -> defaults.requiresApproval("GB", 1));
        assertThrows(IllegalArgumentException.class, () -> defaults.requiresApproval(null, 1));
    }
}