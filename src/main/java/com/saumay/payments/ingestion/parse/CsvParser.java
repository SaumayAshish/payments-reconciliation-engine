package com.saumay.payments.ingestion.parse;

import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.ingestion.RejectedLine;
import com.saumay.payments.ingestion.normalise.LineErrorCode;
import com.saumay.payments.ingestion.normalise.RawLine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Strict CSV reader for statement and ledger files (FRD 4.2, 4.3). A pure function of the bytes: no clock, no
 * randomness, no locale, so the same bytes always give the same result (ADR-0003).
 *
 * <p>Structure only. Whether an amount or date is valid is the normaliser's job; this class produces
 * {@link RawLine}s. Quoting follows RFC 4180: fields may be quoted, a doubled quote is a literal quote, and a
 * quoted field may contain commas and line breaks.
 */
public final class CsvParser {

    private static final List<String> REQUIRED = List.of(
            "account", "booking_date", "value_date", "amount", "currency", "reference", "counterparty", "narrative");
    private static final String ENTRY_ID = "entry_id";

    private CsvParser() {
    }

    public static ParsedFile parse(byte[] content, LineSource kind) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(kind, "kind");
        if (content.length == 0) {
            throw new FileRejectedException(FileErrorCode.EMPTY_FILE, "The file is empty");
        }

        List<CsvRecord> records = tokenise(Utf8.decode(content));
        if (records.isEmpty()) {
            throw new FileRejectedException(FileErrorCode.EMPTY_FILE, "The file contains no records");
        }
        Map<String, Integer> columns = readHeader(records.get(0), kind);
        if (records.size() == 1) {
            throw new FileRejectedException(FileErrorCode.NO_DATA_ROWS, "The file has a header but no data rows");
        }

        List<ParsedRow> rows = new ArrayList<>();
        List<RejectedLine> rejected = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            CsvRecord record = records.get(i);
            if (record.malformed()) {
                rejected.add(new RejectedLine(i, LineErrorCode.MALFORMED_ROW,
                        "row has invalid quoting", record.raw()));
            } else if (record.fields().size() != columns.size()) {
                rejected.add(new RejectedLine(i, LineErrorCode.MALFORMED_ROW,
                        "expected " + columns.size() + " columns but found " + record.fields().size(),
                        record.raw()));
            } else {
                rows.add(toRow(i, record, columns));
            }
        }
        return new ParsedFile(rows, rejected);
    }

    private static ParsedRow toRow(int lineNumber, CsvRecord record, Map<String, Integer> columns) {
        List<String> f = record.fields();
        RawLine raw = new RawLine(
                f.get(columns.get("account")),
                f.get(columns.get("booking_date")),
                f.get(columns.get("value_date")),
                f.get(columns.get("amount")),
                f.get(columns.get("currency")),
                f.get(columns.get("reference")),
                f.get(columns.get("counterparty")),
                f.get(columns.get("narrative")));
        String entryId = null;
        Integer entryIdColumn = columns.get(ENTRY_ID);
        if (entryIdColumn != null) {
            String value = f.get(entryIdColumn).strip();
            entryId = value.isEmpty() ? null : value;
        }
        return new ParsedRow(lineNumber, raw, entryId, record.raw());
    }

    private static Map<String, Integer> readHeader(CsvRecord header, LineSource kind) {
        if (header.malformed()) {
            throw new FileRejectedException(FileErrorCode.INVALID_HEADER, "The header row has invalid quoting");
        }
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.fields().size(); i++) {
            String name = header.fields().get(i).strip().toLowerCase(Locale.ROOT);
            boolean known = REQUIRED.contains(name) || (kind == LineSource.LEDGER && name.equals(ENTRY_ID));
            if (!known) {
                throw new FileRejectedException(FileErrorCode.INVALID_HEADER,
                        "The header contains an unsupported column");
            }
            if (columns.put(name, i) != null) {
                throw new FileRejectedException(FileErrorCode.INVALID_HEADER,
                        "The header contains a duplicate column: " + name);
            }
        }
        for (String required : REQUIRED) {
            if (!columns.containsKey(required)) {
                throw new FileRejectedException(FileErrorCode.INVALID_HEADER,
                        "The header is missing the required column: " + required);
            }
        }
        return columns;
    }

    /** Splits text into records. Blank lines are skipped. Throws if a quoted field is never closed. */
    private static List<CsvRecord> tokenise(String text) {
        List<CsvRecord> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean quoted = false;
        boolean started = false;
        boolean malformed = false;
        int recordStart = 0;
        int n = text.length();

        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    if (!quoted && !started) {
                        inQuotes = true;
                        quoted = true;
                    } else {
                        malformed = true;
                        field.append(c);
                    }
                }
                case ',' -> {
                    fields.add(field.toString());
                    field.setLength(0);
                    quoted = false;
                    started = false;
                }
                case '\r', '\n' -> {
                    int end = i;
                    if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    finishRecord(records, fields, field, quoted, malformed, text.substring(recordStart, end));
                    quoted = false;
                    started = false;
                    malformed = false;
                    recordStart = i + 1;
                }
                default -> {
                    if (quoted) {
                        malformed = true;
                    }
                    field.append(c);
                    started = true;
                }
            }
        }
        if (inQuotes) {
            throw new FileRejectedException(FileErrorCode.MALFORMED_CSV, "A quoted field is never closed");
        }
        if (recordStart < n) {
            finishRecord(records, fields, field, quoted, malformed, text.substring(recordStart, n));
        }
        return records;
    }

    private static void finishRecord(List<CsvRecord> records, List<String> fields, StringBuilder field,
                                     boolean quoted, boolean malformed, String raw) {
        fields.add(field.toString());
        field.setLength(0);
        boolean blank = fields.size() == 1 && fields.get(0).isEmpty() && !quoted;
        if (!blank) {
            records.add(new CsvRecord(List.copyOf(fields), malformed, raw));
        }
        fields.clear();
    }

    private record CsvRecord(List<String> fields, boolean malformed, String raw) {
    }
}
