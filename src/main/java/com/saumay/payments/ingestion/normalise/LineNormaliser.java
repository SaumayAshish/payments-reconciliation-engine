package com.saumay.payments.ingestion.normalise;

import com.saumay.payments.domain.model.CanonicalLine;
import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineKey;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.domain.money.CurrencyCode;
import com.saumay.payments.domain.money.Money;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a {@link RawLine} into a {@link CanonicalLine} or throws {@link LineValidationException} with a specific
 * {@link LineErrorCode} (FRD-02). A pure function: the same input always yields the same output, and checks run
 * in a fixed order so the reported error is deterministic.
 */
public final class LineNormaliser {

    private static final Pattern ACCOUNT = Pattern.compile("[A-Z0-9-]{1,34}");
    private static final Pattern ISO_DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    private LineNormaliser() {
    }

    public static CanonicalLine normalise(LineSource source, ContentHash fileHash, int lineNumber, RawLine raw) {
        String account = strip(raw.account()).toUpperCase(Locale.ROOT);
        if (!ACCOUNT.matcher(account).matches()) {
            throw new LineValidationException(LineErrorCode.INVALID_ACCOUNT,
                    "account must be 1 to 34 characters of A-Z, 0-9 or '-'");
        }

        CurrencyCode currency = parseCurrency(raw.currency());
        LocalDate valueDate = parseDate("value_date", raw.valueDate(), true);
        LocalDate bookingDate = parseDate("booking_date", raw.bookingDate(), false);

        long minorUnits = AmountParser.parseMinorUnits(raw.amount(), currency);
        if (minorUnits == 0L) {
            throw new LineValidationException(LineErrorCode.AMOUNT_ZERO, "amount must not be zero");
        }

        String reference = strip(raw.reference());
        String referenceNorm = ReferenceNormaliser.normalise(reference);
        if (referenceNorm.isEmpty()) {
            throw new LineValidationException(LineErrorCode.MISSING_REFERENCE,
                    "reference must contain at least one letter or digit");
        }

        String counterparty = strip(raw.counterparty());
        String narrative = strip(raw.narrative());
        if (narrative.length() > CanonicalLine.MAX_NARRATIVE_LENGTH) {
            throw new LineValidationException(LineErrorCode.NARRATIVE_TOO_LONG,
                    "narrative exceeds " + CanonicalLine.MAX_NARRATIVE_LENGTH + " characters");
        }

        return new CanonicalLine(
                source,
                account,
                Money.of(minorUnits, currency),
                valueDate,
                bookingDate,
                reference,
                referenceNorm,
                counterparty,
                CounterpartyNormaliser.normalise(counterparty),
                narrative,
                LineKey.of(fileHash, lineNumber));
    }

    private static CurrencyCode parseCurrency(String text) {
        String code = strip(text).toUpperCase(Locale.ROOT);
        try {
            return CurrencyCode.of(code);
        } catch (IllegalArgumentException e) {
            throw new LineValidationException(LineErrorCode.INVALID_CURRENCY,
                    "currency must be a valid ISO 4217 code");
        }
    }

    private static LocalDate parseDate(String field, String text, boolean required) {
        String trimmed = strip(text);
        if (trimmed.isEmpty()) {
            if (required) {
                throw new LineValidationException(LineErrorCode.INVALID_DATE, field + " is missing");
            }
            return null;
        }
        if (!ISO_DATE.matcher(trimmed).matches()) {
            throw new LineValidationException(LineErrorCode.INVALID_DATE, field + " must be YYYY-MM-DD");
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new LineValidationException(LineErrorCode.INVALID_DATE, field + " is not a real calendar date");
        }
    }

    private static String strip(String text) {
        return text == null ? "" : text.strip();
    }
}