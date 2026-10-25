package com.saumay.payments.domain.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.money.CurrencyCode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutingConfigTest {

    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final CurrencyCode JPY = CurrencyCode.of("JPY");

    @Test
    void theDefaultsUseOneMillionMinorUnitsAndTheStandardSla() {
        RoutingConfig config = RoutingConfig.defaults();
        assertEquals(1_000_000L, config.defaultHighValueThresholdMinor());
        assertEquals(1_000_000L, config.thresholdFor(GBP));
        assertFalse(config.hasThresholdFor(GBP));
        assertEquals(SlaPolicy.standard(), config.sla());
    }

    @Test
    void aCurrencyWithItsOwnThresholdUsesIt() {
        RoutingConfig config = new RoutingConfig(Map.of(JPY, 100_000L), 1_000_000L, SlaPolicy.standard());
        assertTrue(config.hasThresholdFor(JPY));
        assertEquals(100_000L, config.thresholdFor(JPY));
        assertEquals(1_000_000L, config.thresholdFor(GBP));
    }

    @Test
    void laterChangesToTheSourceMapDoNotLeakIn() {
        Map<CurrencyCode, Long> source = new HashMap<>();
        source.put(JPY, 100_000L);
        RoutingConfig config = new RoutingConfig(source, 1_000_000L, SlaPolicy.standard());
        source.put(GBP, 5L);
        source.put(JPY, 1L);
        assertEquals(100_000L, config.thresholdFor(JPY));
        assertFalse(config.hasThresholdFor(GBP));
        assertThrows(UnsupportedOperationException.class,
                () -> config.highValueThresholdMinorByCurrency().put(GBP, 5L));
    }

    @Test
    void invalidConfigurationIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new RoutingConfig(Map.of(), 0L, SlaPolicy.standard()));
        assertThrows(IllegalArgumentException.class,
                () -> new RoutingConfig(Map.of(GBP, 0L), 1_000_000L, SlaPolicy.standard()));
        assertThrows(IllegalArgumentException.class,
                () -> new RoutingConfig(Map.of(GBP, -5L), 1_000_000L, SlaPolicy.standard()));
        assertThrows(NullPointerException.class, () -> new RoutingConfig(null, 1_000_000L, SlaPolicy.standard()));
        assertThrows(NullPointerException.class, () -> new RoutingConfig(Map.of(), 1_000_000L, null));
    }
}