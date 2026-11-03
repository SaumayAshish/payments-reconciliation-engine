package com.saumay.payments.domain.audit;

import java.util.Map;
import java.util.TreeMap;
import java.util.List;

/**
 * Writes the canonical JSON text that audit states are stored and hashed as (ADR-0010 points 4 and 5).
 *
 * <p>Canonical means one value has exactly one text: object keys sorted by UTF-16 code unit order, no whitespace,
 * integers as plain digits, {@code true}, {@code false}, {@code null}, arrays in their given order, and strings
 * escaped by a fixed rule (below). The text is stored as-is, so the hash never depends on a database re-encoding it.
 *
 * <p>Only {@code null}, {@code Boolean}, {@code Byte}, {@code Short}, {@code Integer}, {@code Long}, {@code String},
 * {@code Map} (with non-null {@code String} keys) and {@code List} are accepted. Anything else, floating-point
 * numbers in particular, is refused, so an imprecise or ambiguously formatted number cannot enter an audited state
 * (money travels as minor units and a currency code).
 *
 * <p>String rule: a double quote and a backslash are escaped with a backslash; backspace, form feed, newline,
 * carriage return and tab are written as a backslash followed by b, f, n, r or t; every other character below
 * U+0020 is a backslash, the letter u, two zeros and two lower-case hex digits; all remaining characters are
 * written verbatim. A string with an unpaired surrogate is refused because it has no UTF-8 form and would be
 * silently altered when hashed.
 *
 * <p>This encoding is a compatibility contract: changing it changes every future hash. A change needs a new
 * audit scheme number (see {@link AuditChain}).
 */
public final class CanonicalJson {

    private static final int MAX_DEPTH = 32;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CanonicalJson() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value, 0);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("audited state is nested deeper than " + MAX_DEPTH + " levels");
        }
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte) {
            out.append(((Number) value).longValue());
        } else if (value instanceof String text) {
            appendString(out, text);
        } else if (value instanceof Map<?, ?> map) {
            appendObject(out, map, depth);
        } else if (value instanceof List<?> list) {
            appendArray(out, list, depth);
        } else {
            throw new IllegalArgumentException(
                    "type not allowed in an audited state: " + value.getClass().getName());
        }
    }

    private static void appendObject(StringBuilder out, Map<?, ?> map, int depth) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("object keys in an audited state must be non-null strings");
            }
            sorted.put(key, entry.getValue());
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            appendString(out, entry.getKey());
            out.append(':');
            append(out, entry.getValue(), depth + 1);
        }
        out.append('}');
    }

    private static void appendArray(StringBuilder out, List<?> list, int depth) {
        out.append('[');
        boolean first = true;
        for (Object element : list) {
            if (!first) {
                out.append(',');
            }
            first = false;
            append(out, element, depth + 1);
        }
        out.append(']');
    }

    private static void appendString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new IllegalArgumentException("unpaired surrogate in an audited string");
                }
                out.append(c).append(text.charAt(++i));
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("unpaired surrogate in an audited string");
            }
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\").append("u00").append(HEX[c >> 4]).append(HEX[c & 0xF]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}