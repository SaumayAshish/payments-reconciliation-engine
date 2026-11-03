package com.saumay.payments.domain.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

    private static final String EURO_E = "" + (char) 0xE9 + (char) 0x20AC;
    private static final String HIGH = String.valueOf((char) 0xD800);
    private static final String LOW = String.valueOf((char) 0xDC00);

    @Test
    void sortsKeysAndWritesNoWhitespace() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", "OPEN");
        state.put("owner", null);
        state.put("amountMinor", 1250L);
        assertEquals("{\"amountMinor\":1250,\"owner\":null,\"status\":\"OPEN\"}", CanonicalJson.write(state));
    }

    @Test
    void nestsObjectsAndArraysKeepingArrayOrder() {
        Map<String, Object> inner = new HashMap<>();
        inner.put("y", null);
        inner.put("x", true);
        Map<String, Object> state = new HashMap<>();
        state.put("z", List.of(3, inner, "s"));
        state.put("a", false);
        assertEquals("{\"a\":false,\"z\":[3,{\"x\":true,\"y\":null},\"s\"]}", CanonicalJson.write(state));
    }

    @Test
    void emptyContainersAndBareValues() {
        assertEquals("{}", CanonicalJson.write(new HashMap<String, Object>()));
        assertEquals("[]", CanonicalJson.write(List.of()));
        assertEquals("null", CanonicalJson.write(null));
        assertEquals("true", CanonicalJson.write(true));
    }

    @Test
    void integersOfEveryWidthArePlainDigits() {
        assertEquals("[7,8,9,10,-11]", CanonicalJson.write(List.of((byte) 7, (short) 8, 9, 10L, -11L)));
        assertEquals("[" + Long.MAX_VALUE + "," + Long.MIN_VALUE + "]",
                CanonicalJson.write(List.of(Long.MAX_VALUE, Long.MIN_VALUE)));
    }

    @Test
    void insertionOrderNeverChangesTheOutput() {
        Map<String, Object> hash = new HashMap<>();
        Map<String, Object> ascending = new LinkedHashMap<>();
        Map<String, Object> descending = new TreeMap<>(Collections.reverseOrder());
        for (String key : List.of("b", "a", "d", "c", "B", "A", "_", "0")) {
            hash.put(key, key);
            ascending.put(key, key);
            descending.put(key, key);
        }
        String expected = CanonicalJson.write(hash);
        assertEquals(expected, CanonicalJson.write(ascending));
        assertEquals(expected, CanonicalJson.write(descending));
        assertEquals("{\"0\":\"0\",\"A\":\"A\",\"B\":\"B\",\"_\":\"_\",\"a\":\"a\",\"b\":\"b\",\"c\":\"c\","
                + "\"d\":\"d\"}", expected);
    }

    @Test
    void stringsUseTheFixedEscapingRuleAndKeepOtherCharactersVerbatim() {
        String control = String.valueOf((char) 1);
        String text = "a\"b\\c\n\t" + control + EURO_E;
        String expected = "\"a\\\"b\\\\c\\n\\t" + "\\" + "u0001" + EURO_E + "\"";
        assertEquals(expected, CanonicalJson.write(text));
        assertEquals("\"\\b\\f\\r\"", CanonicalJson.write("\b\f\r"));
        assertEquals("\"" + "\\" + "u001f\"", CanonicalJson.write(String.valueOf((char) 0x1f)));
        String del = String.valueOf((char) 0x7F);
        assertEquals("\"" + del + "\"", CanonicalJson.write(del));
    }

    @Test
    void aValidSurrogatePairIsWrittenVerbatim() {
        String emoji = new String(Character.toChars(0x1F600));
        assertEquals("\"" + emoji + "\"", CanonicalJson.write(emoji));
    }

    @Test
    void unpairedSurrogatesAreRefusedBecauseUtf8CannotRepresentThem() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(HIGH));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(HIGH + "x"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(LOW));
    }

    @Test
    void floatingPointAndDecimalNumbersAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Double.valueOf(1.5)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Float.valueOf(1.5f)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(List.of(1, 2.0)));
    }

    @Test
    void otherTypesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(new Object()));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write('c'));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(new int[] {1}));
    }

    @Test
    void objectKeysMustBeNonNullStrings() {
        Map<Object, Object> numeric = new HashMap<>();
        numeric.put(1, "x");
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(numeric));
        Map<String, Object> nullKey = new HashMap<>();
        nullKey.put(null, "x");
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(nullKey));
    }

    @Test
    void nestingIsBoundedSoAStateCannotOverflowTheStack() {
        assertEquals("[".repeat(32) + "1" + "]".repeat(32), CanonicalJson.write(nest(32)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(nest(33)));
    }

    @Test
    void randomStructuresWriteIdenticallyWhateverTheKeyInsertionOrder() {
        Random random = new Random(20261103L);
        for (int round = 0; round < 300; round++) {
            Object original = randomValue(random, 0);
            Object reordered = reverseKeyOrder(original);
            String first = CanonicalJson.write(original);
            assertEquals(first, CanonicalJson.write(reordered), "round " + round);
            assertEquals(first, CanonicalJson.write(original), "writing twice, round " + round);
            assertFalse(first.contains("\n") || first.contains("\r") || first.contains("\t"),
                    "raw control characters must be escaped, round " + round);
        }
    }

    private static Object nest(int levels) {
        Object value = 1;
        for (int i = 0; i < levels; i++) {
            value = List.of(value);
        }
        return value;
    }

    private static Object randomValue(Random random, int depth) {
        int kind = random.nextInt(depth >= 3 ? 5 : 7);
        return switch (kind) {
            case 0 -> null;
            case 1 -> random.nextBoolean();
            case 2 -> random.nextLong();
            case 3 -> random.nextInt(1000) - 500;
            case 4 -> randomText(random);
            case 5 -> {
                List<Object> list = new ArrayList<>();
                for (int i = random.nextInt(4); i > 0; i--) {
                    list.add(randomValue(random, depth + 1));
                }
                yield list;
            }
            default -> {
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = random.nextInt(5); i > 0; i--) {
                    map.put(randomText(random), randomValue(random, depth + 1));
                }
                yield map;
            }
        };
    }

    private static String randomText(Random random) {
        String alphabet = "abcXYZ019 _-\"\\\n\t" + EURO_E + (char) 1;
        StringBuilder text = new StringBuilder();
        for (int i = random.nextInt(6); i > 0; i--) {
            text.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return text.toString();
    }

    private static Object reverseKeyOrder(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            Collections.reverse(entries);
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : entries) {
                copy.put(entry.getKey(), reverseKeyOrder(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object element : list) {
                copy.add(reverseKeyOrder(element));
            }
            return copy;
        }
        return value;
    }
}