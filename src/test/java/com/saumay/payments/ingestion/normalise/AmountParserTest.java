package com.saumay.payments.ingestion.normalise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saumay.payments.domain.money.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AmountParserTest {

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "1250.50,GBP,125050",
            "-1250.50,GBP,-125050",
            "0.01,GBP,1",
            "10.5,GBP,1050",
            "1500,JPY,1500",
            "1500.00,JPY,1500",
            "1.234,KWD,1234",
            "+5,GBP,500",
            "0.00,GBP,0",
            "92233720368547758.07,GBP,9223372036854775807"
    })
    void convertsDecimalTextToExactMinorUnits(String text, String currency, long expected) {
        assertEquals(expected, AmountParser.parseMinorUnits(text, CurrencyCode.of(currency)));
    }

    @Test
    void ignoresSurroundingWhitespace() {
        assertEquals(1230, AmountParser.parseMinorUnits(" 12.30 ", CurrencyCode.of("GBP")));
    }

    @ParameterizedTest(name = "{0} {1} is rejected as {2}")
    @CsvSource({
            "10.005,GBP,AMOUNT_PRECISION",
            "1.5,JPY,AMOUNT_PRECISION",
            "1.2345,KWD,AMOUNT_PRECISION",
            "1e3,GBP,INVALID_AMOUNT",
            "'1,250.50',GBP,INVALID_AMOUNT",
            "abc,GBP,INVALID_AMOUNT",
            ".5,GBP,INVALID_AMOUNT",
            "92233720368547758.08,GBP,AMOUNT_RANGE",
            "-92233720368547758.08,GBP,AMOUNT_RANGE"
    })
    void rejectsInsteadOfRoundingOrGuessing(String text, String currency, LineErrorCode expected) {
        LineValidationException e = assertThrows(LineValidationException.class,
                () -> AmountParser.parseMinorUnits(text, CurrencyCode.of(currency)));
        assertEquals(expected, e.code());
    }

    @Test
    void rejectsMissingBlankFullWidthDigitsAndAbsurdlyLongInput() {
        CurrencyCode gbp = CurrencyCode.of("GBP");
        // "\uFF11\uFF12" is the full-width digits 1 and 2.
        for (String bad : new String[] {null, "", "   ", "\uFF11\uFF12", "9".repeat(50)}) {
            LineValidationException e =
                    assertThrows(LineValidationException.class, () -> AmountParser.parseMinorUnits(bad, gbp));
            assertEquals(LineErrorCode.INVALID_AMOUNT, e.code());
        }
    }
}