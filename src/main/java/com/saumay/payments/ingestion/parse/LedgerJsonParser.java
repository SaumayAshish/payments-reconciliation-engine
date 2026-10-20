package com.saumay.payments.ingestion.parse;

import com.saumay.payments.ingestion.normalise.RawLine;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Strict reader for the ledger JSON body (FRD 4.3, AC-03.2): an array of flat objects whose values are JSON strings
 * or null. Numbers, booleans, nested values, unknown fields and duplicate fields refuse the whole file, so money can
 * never arrive as a parsed number (ADR-0001).
 *
 * <p>The file identity is the canonical form: compact, fixed field order, null and missing both written as an empty
 * string. Two bodies that differ only in key order or whitespace therefore have the same hash. Line numbers are
 * positions in the array, from 1. A pure function of the bytes.
 */
public final class LedgerJsonParser {

    private static final List<String> FIELDS = List.of(
            "account", "booking_date", "value_date", "amount", "currency", "reference", "counterparty",
            "narrative", "entry_id");

    private LedgerJsonParser() {
    }

    public static LedgerJsonDocument parse(byte[] content) {
        Objects.requireNonNull(content, "content");
        if (content.length == 0) {
            throw new FileRejectedException(FileErrorCode.EMPTY_FILE, "The file is empty");
        }
        Reader in = new Reader(Utf8.decode(content));
        List<Map<String, String>> entries = new ArrayList<>();

        in.skipWhitespace();
        in.expect('[');
        in.skipWhitespace();
        if (in.peek() == ']') {
            in.advance();
        } else {
            while (true) {
                in.skipWhitespace();
                entries.add(in.readObject());
                in.skipWhitespace();
                char c = in.next();
                if (c == ']') {
                    break;
                }
                if (c != ',') {
                    throw in.error("expected ',' or ']'");
                }
            }
        }
        in.skipWhitespace();
        if (!in.atEnd()) {
            throw in.error("unexpected content after the closing ']'");
        }
        if (entries.isEmpty()) {
            throw new FileRejectedException(FileErrorCode.NO_DATA_ROWS, "The file contains no entries");
        }
        return toDocument(entries);
    }

    private static LedgerJsonDocument toDocument(List<Map<String, String>> entries) {
        StringBuilder canonical = new StringBuilder("[");
        List<ParsedRow> rows = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Map<String, String> entry = entries.get(i);
            String json = canonicalObject(entry);
            if (i > 0) {
                canonical.append(',');
            }
            canonical.append(json);
            RawLine raw = new RawLine(
                    get(entry, "account"), get(entry, "booking_date"), get(entry, "value_date"),
                    get(entry, "amount"), get(entry, "currency"), get(entry, "reference"),
                    get(entry, "counterparty"), get(entry, "narrative"));
            String entryId = get(entry, "entry_id").strip();
            rows.add(new ParsedRow(i + 1, raw, entryId.isEmpty() ? null : entryId, json));
        }
        canonical.append(']');
        return new LedgerJsonDocument(new ParsedFile(rows, List.of()),
                canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String canonicalObject(Map<String, String> entry) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < FIELDS.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            String field = FIELDS.get(i);
            json.append('"').append(field).append("\":");
            appendString(json, get(entry, field));
        }
        return json.append('}').toString();
    }

    private static void appendString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                out.append("\\\"");
            } else if (c == '\\') {
                out.append("\\\\");
            } else if (c < 0x20) {
                out.append("\\u00")
                        .append(Character.forDigit(c >> 4, 16))
                        .append(Character.forDigit(c & 0xF, 16));
            } else {
                out.append(c);
            }
        }
        out.append('"');
    }

    private static String get(Map<String, String> entry, String field) {
        return entry.getOrDefault(field, "");
    }

    /** Cursor over the decoded text. */
    private static final class Reader {

        private final String text;
        private int pos;

        Reader(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        char peek() {
            return atEnd() ? '\0' : text.charAt(pos);
        }

        void advance() {
            pos++;
        }

        char next() {
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            return text.charAt(pos++);
        }

        void expect(char expected) {
            if (next() != expected) {
                pos--;
                throw error("expected '" + expected + "'");
            }
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        FileRejectedException error(String problem) {
            return new FileRejectedException(FileErrorCode.MALFORMED_JSON,
                    "Invalid JSON: " + problem + " (at offset " + pos + ")");
        }

        Map<String, String> readObject() {
            expect('{');
            Map<String, String> values = new HashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                advance();
                return values;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                if (!FIELDS.contains(key)) {
                    throw error("unsupported field");
                }
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if (values.put(key, readValue()) != null) {
                    throw error("duplicate field " + key);
                }
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return values;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private String readValue() {
            if (peek() == '"') {
                return readString();
            }
            if (text.startsWith("null", pos)) {
                pos += 4;
                return "";
            }
            throw error("field values must be JSON strings or null");
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw error("control character inside a string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char escape = next();
                switch (escape) {
                    case '"', '\\', '/' -> sb.append(escape);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> sb.append(readHex4());
                    default -> throw error("invalid escape sequence");
                }
            }
        }

        private char readHex4() {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                char h = next();
                int digit = h < 128 ? Character.digit(h, 16) : -1;
                if (digit < 0) {
                    throw error("invalid unicode escape");
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }
    }
}