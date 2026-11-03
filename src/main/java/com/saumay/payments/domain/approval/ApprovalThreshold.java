package com.saumay.payments.domain.approval;

import java.util.Map;
import java.util.Objects;

/**
 * The value above which a write-off or adjustment needs a second approver (FRD-08). The comparison is on the absolute
 * amount and strictly greater: an amount equal to the threshold does not need approval.
 */
public record ApprovalThreshold(long defaultMinor, Map<String, Long> perCurrencyMinor) {

    /** 10,000.00 in a two-decimal currency. A design default: the BRD gives no figure. */
    public static final long DEFAULT_MINOR = 1_000_000L;

    public ApprovalThreshold {
        if (defaultMinor < 0) {
            throw new IllegalArgumentException("the default threshold cannot be negative");
        }
        Objects.requireNonNull(perCurrencyMinor, "perCurrencyMinor");
        perCurrencyMinor = Map.copyOf(perCurrencyMinor);
        perCurrencyMinor.forEach((currency, value) -> {
            requireCurrency(currency);
            if (value < 0) {
                throw new IllegalArgumentException("the threshold for " + currency + " cannot be negative");
            }
        });
    }

    public static ApprovalThreshold defaults() {
        return new ApprovalThreshold(DEFAULT_MINOR, Map.of());
    }

    public long thresholdFor(String currency) {
        requireCurrency(currency);
        return perCurrencyMinor.getOrDefault(currency, defaultMinor);
    }

    public boolean requiresApproval(String currency, long amountMinor) {
        long threshold = thresholdFor(currency);
        // threshold >= 0, so -threshold cannot overflow; this also handles Long.MIN_VALUE.
        return amountMinor > threshold || amountMinor < -threshold;
    }

    private static void requireCurrency(String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be three upper-case letters");
        }
    }
}