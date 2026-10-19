package com.saumay.payments.ingestion.normalise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class NormaliserTest {

    @Test
    void referenceKeepsOnlyUpperCaseLettersAndDigits() {
        assertEquals("INV20260042", ReferenceNormaliser.normalise(" inv-2026/0042 "));
    }

    @Test
    void referencePreservesLeadingZeros() {
        assertEquals("00123", ReferenceNormaliser.normalise("00123"));
    }

    @Test
    void referenceFoldsFullWidthCharactersAndDropsZeroWidthOnes() {
        // Full-width "INV123", written as escapes so the editor cannot alter it.
        assertEquals("INV123", ReferenceNormaliser.normalise("\uFF29\uFF2E\uFF36\uFF11\uFF12\uFF13"));
        // A zero-width space between B and C.
        assertEquals("ABC", ReferenceNormaliser.normalise("AB\u200BC"));
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", ReferenceNormaliser.normalise(null));
        assertEquals("", CounterpartyNormaliser.normalise(null));
    }

    @Test
    void counterpartyDropsLegalFormsAndPunctuation() {
        assertEquals("ACME TRADING", CounterpartyNormaliser.normalise("Acme Trading Ltd."));
        assertEquals("NORTHWIND TRADING", CounterpartyNormaliser.normalise("  northwind   TRADING, Pvt. Ltd "));
        assertEquals("", CounterpartyNormaliser.normalise("Ltd"));
    }

    @Test
    void bothNormalisersAreIdempotentAndReferenceOutputIsAlwaysAlphanumeric() {
        SplittableRandom random = new SplittableRandom(42L);
        int[][] ranges = {{0x20, 0x24F}, {0xFF00, 0xFFEF}, {0x200B, 0x200F}, {0x1F600, 0x1F64F}};
        Pattern alphanumeric = Pattern.compile("[A-Z0-9]*");
        for (int i = 0; i < 20_000; i++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(0, 24);
            for (int j = 0; j < length; j++) {
                int[] range = ranges[random.nextInt(ranges.length)];
                text.appendCodePoint(random.nextInt(range[0], range[1] + 1));
            }
            String reference = ReferenceNormaliser.normalise(text.toString());
            String counterparty = CounterpartyNormaliser.normalise(text.toString());
            assertEquals(reference, ReferenceNormaliser.normalise(reference));
            assertEquals(counterparty, CounterpartyNormaliser.normalise(counterparty));
            assertTrue(alphanumeric.matcher(reference).matches(), "not alphanumeric for case " + i);
        }
    }
}