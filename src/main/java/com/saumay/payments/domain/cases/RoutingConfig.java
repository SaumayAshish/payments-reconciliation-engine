package com.saumay.payments.domain.cases;

import com.saumay.payments.domain.money.CurrencyCode;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Every tunable number behind case routing and SLA (ADR-0008): the high-value threshold per currency in minor
 * units, the threshold used for a currency with none of its own, and the SLA policy. Immutable and validated; a
 * changed configuration is a new instance, and versioning and audit arrive in Phase 5.
 */
public record RoutingConfig(
        Map<CurrencyCode, Long> highValueThresholdMinorByCurrency,
        long defaultHighValueThresholdMinor,
        SlaPolicy sla) {

    /** A placeholder for synthetic data (ADR-0008); it must be reviewed with the business before real use. */
    public static final long STANDARD_DEFAULT_HIGH_VALUE_MINOR = 1_000_000L;

    public RoutingConfig {
        Objects.requireNonNull(highValueThresholdMinorByCurrency, "highValueThresholdMinorByCurrency");
        Objects.requireNonNull(sla, "sla");
        if (defaultHighValueThresholdMinor < 1) {
            throw new IllegalArgumentException("The default high-value threshold must be at least 1 minor unit");
        }
        TreeMap<CurrencyCode, Long> copy = new TreeMap<>();
        for (Map.Entry<CurrencyCode, Long> entry : highValueThresholdMinorByCurrency.entrySet()) {
            CurrencyCode currency = Objects.requireNonNull(entry.getKey(), "currency");
            Long threshold = Objects.requireNonNull(entry.getValue(), "threshold");
            if (threshold < 1) {
                throw new IllegalArgumentException("The high-value threshold for " + currency
                        + " must be at least 1 minor unit");
            }
            copy.put(currency, threshold);
        }
        highValueThresholdMinorByCurrency = Collections.unmodifiableSortedMap(copy);
    }

    /** No per-currency overrides, the standard default threshold and the Q-10 SLA policy. */
    public static RoutingConfig defaults() {
        return new RoutingConfig(Map.of(), STANDARD_DEFAULT_HIGH_VALUE_MINOR, SlaPolicy.standard());
    }

    /** True when the currency has its own threshold; false means the default applies (EC-06.1, log a warning). */
    public boolean hasThresholdFor(CurrencyCode currency) {
        return highValueThresholdMinorByCurrency.containsKey(currency);
    }

    public long thresholdFor(CurrencyCode currency) {
        return highValueThresholdMinorByCurrency.getOrDefault(currency, defaultHighValueThresholdMinor);
    }
}