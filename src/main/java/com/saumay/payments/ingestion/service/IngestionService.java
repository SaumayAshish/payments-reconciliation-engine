package com.saumay.payments.ingestion.service;

import com.saumay.payments.domain.model.CanonicalLine;
import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.ingestion.AcceptedLine;
import com.saumay.payments.ingestion.FileFormat;
import com.saumay.payments.ingestion.IngestionOutcome;
import com.saumay.payments.ingestion.IngestionRequest;
import com.saumay.payments.ingestion.IngestionStore;
import com.saumay.payments.ingestion.RejectedLine;
import com.saumay.payments.ingestion.normalise.LineErrorCode;
import com.saumay.payments.ingestion.normalise.LineNormaliser;
import com.saumay.payments.ingestion.normalise.LineValidationException;
import com.saumay.payments.ingestion.parse.Camt053Parser;
import com.saumay.payments.ingestion.parse.CsvParser;
import com.saumay.payments.ingestion.parse.FileErrorCode;
import com.saumay.payments.ingestion.parse.FileRejectedException;
import com.saumay.payments.ingestion.parse.LedgerJsonDocument;
import com.saumay.payments.ingestion.parse.LedgerJsonParser;
import com.saumay.payments.ingestion.parse.ParsedFile;
import com.saumay.payments.ingestion.parse.ParsedRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Turns uploaded bytes into a stored ingestion (FRD-01 to FRD-03): size check, content hash, parse, normalise every
 * row, then one atomic idempotent store. Invalid rows are kept as rejected lines, never dropped. A file the parser
 * refuses as a whole never reaches the store, so it leaves no ingestion row (EC-01.5).
 *
 * <p>Plain Java: the same bytes always produce the same {@link IngestionRequest}. For ledger JSON the identity is the
 * canonical form of the entries, not the raw body (AC-03.2).
 */
public final class IngestionService {

    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

    private static final Pattern ENTRY_ID = Pattern.compile("[A-Za-z0-9._:/-]{1,64}");

    private final IngestionStore store;
    private final long maxBytes;

    public IngestionService(IngestionStore store, long maxBytes) {
        this.store = Objects.requireNonNull(store, "store");
        if (maxBytes < 1) {
            throw new IllegalArgumentException("Maximum file size must be positive");
        }
        this.maxBytes = maxBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** A bank statement as CSV or camt.053. */
    public IngestionResult ingestStatement(byte[] content, FileFormat format) {
        Objects.requireNonNull(format, "format");
        if (format == FileFormat.JSON) {
            throw new IllegalArgumentException("JSON is not a statement format");
        }
        requireWithinLimit(content);
        ParsedFile parsed = format == FileFormat.CAMT053
                ? Camt053Parser.parse(content)
                : CsvParser.parse(content, LineSource.STATEMENT);
        return process(LineSource.STATEMENT, format, content, parsed);
    }

    /** Ledger entries as CSV. */
    public IngestionResult ingestLedgerCsv(byte[] content) {
        requireWithinLimit(content);
        return process(LineSource.LEDGER, FileFormat.CSV, content, CsvParser.parse(content, LineSource.LEDGER));
    }

    /** Ledger entries as a JSON array. The file identity is the canonical form of the entries. */
    public IngestionResult ingestLedgerJson(byte[] content) {
        requireWithinLimit(content);
        LedgerJsonDocument document = LedgerJsonParser.parse(content);
        return process(LineSource.LEDGER, FileFormat.JSON, document.canonicalBytes(), document.parsed());
    }

    private void requireWithinLimit(byte[] content) {
        Objects.requireNonNull(content, "content");
        if (content.length > maxBytes) {
            throw new FileRejectedException(FileErrorCode.FILE_TOO_LARGE,
                    "The file exceeds the maximum size of " + maxBytes + " bytes");
        }
    }

    private IngestionResult process(LineSource kind, FileFormat format, byte[] identityBytes, ParsedFile parsed) {
        ContentHash hash = ContentHash.of(identityBytes);

        List<AcceptedLine> accepted = new ArrayList<>();
        List<RejectedLine> rejected = new ArrayList<>(parsed.rejected());
        for (ParsedRow row : parsed.rows()) {
            try {
                CanonicalLine line = LineNormaliser.normalise(kind, hash, row.lineNumber(), row.raw());
                if (row.entryId() != null && !ENTRY_ID.matcher(row.entryId()).matches()) {
                    throw new LineValidationException(LineErrorCode.INVALID_ENTRY_ID,
                            "entry_id must be 1 to 64 characters of letters, digits, '.', '_', ':', '/' or '-'");
                }
                accepted.add(new AcceptedLine(row.lineNumber(), line, row.entryId()));
            } catch (LineValidationException e) {
                rejected.add(new RejectedLine(row.lineNumber(), e.code(), e.getMessage(), row.rawText()));
            }
        }

        IngestionOutcome outcome = store.store(
                new IngestionRequest(kind, hash, format, identityBytes.length, accepted, rejected));
        return new IngestionResult(outcome, parsed.ignoredElements());
    }
}
