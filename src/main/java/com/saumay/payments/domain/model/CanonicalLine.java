package com.saumay.payments.domain.model;

import com.saumay.payments.domain.money.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The normalised form of a statement line or ledger entry (FRD section 4.1). Both sides share this shape so the
 * matching engine treats them symmetrically.
 *
 * <p>{@code bookingDate} is the only nullable component: it is never silently copied from the value date.
 * {@code counterparty} and {@code narrative} may be empty but not null. The constructor is a backstop; the
 * ingestion normaliser validates first and reports a specific error code per field.
 */
public record CanonicalLine(
        LineSource source,
        String account,
        Money amount,
        LocalDate valueDate,
        LocalDate bookingDate,
        String reference,
        String referenceNorm,
        String counterparty,
        String counterpartyNorm,
        String narrative,
        LineKey lineKey) {

    public static final int MAX_NARRATIVE_LENGTH = 280;

    private static final Pattern ACCOUNT = Pattern.compile("[A-Z0-9-]{1,34}");

    public CanonicalLine {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(referenceNorm, "referenceNorm");
        Objects.requireNonNull(counterparty, "counterparty");
        Objects.requireNonNull(counterpartyNorm, "counterpartyNorm");
        Objects.requireNonNull(narrative, "narrative");
        Objects.requireNonNull(lineKey, "lineKey");
        if (!ACCOUNT.matcher(account).matches()) {
            throw new IllegalArgumentException("Account must be 1 to 34 characters of A-Z, 0-9 or '-'");
        }
        if (amount.isZero()) {
            throw new IllegalArgumentException("A line amount must not be zero");
        }
        if (reference.isBlank() || referenceNorm.isEmpty()) {
            throw new IllegalArgumentException("A line must have a reference with at least one letter or digit");
        }
        if (narrative.length() > MAX_NARRATIVE_LENGTH) {
            throw new IllegalArgumentException("Narrative exceeds " + MAX_NARRATIVE_LENGTH + " characters");
        }
    }
}