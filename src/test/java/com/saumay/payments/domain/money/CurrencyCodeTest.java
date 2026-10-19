package com.saumay.payments.domain.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CurrencyCodeTest {

    @Test
    void exposesTheMinorUnitExponentOfEachCurrency() {
        assertEquals(2, CurrencyCode.of("GBP").exponent());
        assertEquals(0, CurrencyCode.of("JPY").exponent());
        assertEquals(3, CurrencyCode.of("KWD").exponent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gbp", "XX", "GBPP", "G1P", "", "ZZZ", "XXX"})
    void rejectsMalformedUnknownAndPseudoCurrencies(String code) {
        assertThrows(IllegalArgumentException.class, () -> CurrencyCode.of(code));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> CurrencyCode.of(null));
    }
}