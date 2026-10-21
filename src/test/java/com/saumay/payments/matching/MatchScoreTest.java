package com.saumay.payments.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MatchScoreTest {

    @Test
    void acceptsTheWholeRangeAndRejectsOutsideIt() {
        assertEquals(0, MatchScore.ofMilli(0).milli());
        assertEquals(1000, MatchScore.ofMilli(1000).milli());
        assertThrows(IllegalArgumentException.class, () -> MatchScore.ofMilli(-1));
        assertThrows(IllegalArgumentException.class, () -> MatchScore.ofMilli(1001));
    }

    @Test
    void ordersByValue() {
        assertTrue(MatchScore.ofMilli(949).compareTo(MatchScore.ofMilli(950)) < 0);
        assertTrue(MatchScore.ofMilli(950).compareTo(MatchScore.ofMilli(949)) > 0);
        assertEquals(0, MatchScore.ofMilli(930).compareTo(MatchScore.ofMilli(930)));
    }

    @Test
    void rendersThreeDecimals() {
        assertEquals("1.000", MatchScore.ofMilli(1000).toString());
        assertEquals("0.950", MatchScore.ofMilli(950).toString());
        assertEquals("0.905", MatchScore.ofMilli(905).toString());
        assertEquals("0.000", MatchScore.ofMilli(0).toString());
    }
}