package com.saumay.payments.ingestion.parse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerJsonParserTest {

    private static LedgerJsonDocument parse(String json) {
        return LedgerJsonParser.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String canonical(LedgerJsonDocument document) {
        return new String(document.canonicalBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void theCanonicalFormHasFixedFieldOrderIsCompactAndTreatsNullAndMissingAsEmpty() {
        LedgerJsonDocument document = parse("[ {\"reference\":\"REF-1\",\"account\":\"ACC-1\",\"amount\":\"10.00\","
                + "\"currency\":\"GBP\",\"value_date\":\"2026-10-09\",\"booking_date\":null,"
                + "\"narrative\":\"pay\",\"counterparty\":\"Acme Ltd\"} ]");

        assertEquals("[{\"account\":\"ACC-1\",\"booking_date\":\"\",\"value_date\":\"2026-10-09\","
                + "\"amount\":\"10.00\",\"currency\":\"GBP\",\"reference\":\"REF-1\","
                + "\"counterparty\":\"Acme Ltd\",\"narrative\":\"pay\",\"entry_id\":\"\"}]", canonical(document));
        assertEquals(1, document.parsed().rows().size());
        assertEquals(1, document.parsed().rows().get(0).lineNumber());
        assertEquals("10.00", document.parsed().rows().get(0).raw().amount());
    }

    @Test
    void keyOrderWhitespaceAndNullVersusMissingDoNotChangeTheIdentity() {
        LedgerJsonDocument a = parse("[{\"account\":\"ACC-1\",\"value_date\":\"2026-10-09\",\"amount\":\"10.00\","
                + "\"currency\":\"GBP\",\"reference\":\"REF-1\"}]");
        LedgerJsonDocument b = parse("[\n  {\n    \"reference\": \"REF-1\",\n    \"currency\": \"GBP\",\n"
                + "    \"amount\": \"10.00\",\n    \"booking_date\": null,\n    \"entry_id\": \"\",\n"
                + "    \"value_date\": \"2026-10-09\",\n    \"account\": \"ACC-1\"\n  }\n]\n");

        assertArrayEquals(a.canonicalBytes(), b.canonicalBytes());
    }

    @Test
    void escapesAreDecodedForTheRowsAndRewrittenCanonically() {
        LedgerJsonDocument document = parse("[{\"narrative\":\"a\\nb \\\"q\\\" \\u00e9 \\ud83d\\ude00\"}]");

        assertEquals("a\nb \"q\" \u00e9 \ud83d\ude00", document.parsed().rows().get(0).raw().narrative());
        assertTrue(canonical(document).contains("a\\u000ab \\\"q\\\" \u00e9 \ud83d\ude00"));
    }

    @Test
    void entryIdsAreCarriedAndBlankMeansAbsent() {
        LedgerJsonDocument document = parse("[{\"entry_id\":\"L-1\"},{\"entry_id\":\"\"},{}]");

        assertEquals("L-1", document.parsed().rows().get(0).entryId());
        assertNull(document.parsed().rows().get(1).entryId());
        assertNull(document.parsed().rows().get(2).entryId());
    }

    @Test
    void lineNumbersArePositionsInTheArray() {
        LedgerJsonDocument document = parse("[{},{},{}]");

        assertEquals(List.of(1, 2, 3),
                document.parsed().rows().stream().map(ParsedRow::lineNumber).toList());
    }

    @Test
    void anythingThatIsNotAnArrayOfFlatStringObjectsRefusesTheWholeFile() {
        List<String> bad = List.of(
                "[{\"amount\":10.00}]",
                "[{\"amount\":true}]",
                "[{\"amount\":{\"x\":\"1\"}}]",
                "[{\"amount\":[\"1\"]}]",
                "[{\"surprise\":\"x\"}]",
                "[{\"amount\":\"1\",\"amount\":\"2\"}]",
                "[{\"amount\":\"1\"},]",
                "[{\"amount\":\"1\"}] x",
                "{}",
                "[{\"amount\":\"1\"}{\"amount\":\"2\"}]",
                "[{\"amount\":\"1}]",
                "[{\"narrative\":\"tab\there\"}]",
                "[{\"narrative\":\"bad \\x escape\"}]",
                "[{\"narrative\":\"bad \\u00zz hex\"}]",
                "[{\"account\":\"A\"",
                "not json at all");

        for (String input : bad) {
            FileRejectedException e = assertThrows(FileRejectedException.class, () -> parse(input),
                    "input should be refused: " + input);
            assertEquals(FileErrorCode.MALFORMED_JSON, e.code(), "input: " + input);
        }
    }

    @Test
    void emptyInputsAreRefused() {
        assertEquals(FileErrorCode.EMPTY_FILE,
                assertThrows(FileRejectedException.class, () -> LedgerJsonParser.parse(new byte[0])).code());
        assertEquals(FileErrorCode.NO_DATA_ROWS,
                assertThrows(FileRejectedException.class, () -> parse("[]")).code());
        assertEquals(FileErrorCode.NO_DATA_ROWS,
                assertThrows(FileRejectedException.class, () -> parse("  [ ] \n")).code());
    }

    @Test
    void invalidUtf8IsRefusedNotGuessed() {
        byte[] bytes = {'[', (byte) 0xC3, (byte) 0x28, ']'};

        assertEquals(FileErrorCode.UNSUPPORTED_ENCODING,
                assertThrows(FileRejectedException.class, () -> LedgerJsonParser.parse(bytes)).code());
    }
}