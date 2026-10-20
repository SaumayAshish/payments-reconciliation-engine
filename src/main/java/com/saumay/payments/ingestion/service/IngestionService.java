package com.saumay.payments.ingestion.service;

import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.ingestion.AcceptedLine;
import com.saumay.payments.ingestion.FileFormat;
import com.saumay.payments.ingestion.IngestionOutcome;
import com.saumay.payments.ingestion.IngestionRequest;
import com.saumay.payments.ingestion.IngestionStore;
import com.saumay.payments.ingestion.RejectedLine;
import com.saumay.payments.ingestion.normalise.LineNormaliser;
import com.saumay.payments.ingestion.normalise.LineValidationException;
import com.saumay.payments.ingestion.parse.Camt053Parser;
import com.saumay.payments.ingestion.parse.CsvParser;
import com.saumay.payments.ingestion.parse.FileErrorCode;
import com.saumay.payments.ingestion.parse.FileRejectedException;
import com.saumay.payments.ingestion.parse.ParsedFile;
import com.saumay.payments.ingestion.parse.ParsedRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns uploaded bytes into a stored ingestion (FRD-01 to FRD-03): size check, content hash, parse, normalise every
 * row, then one atomic idempotent store. Invalid rows are kept as rejected lines, never dropped. A file the parser
 * refuses as a whole never reaches the store, so it leaves no ingestion row (EC-01.5).
 *
 * <p>Plain Java: the same bytes always produce the same {@link IngestionRequest}.
 */
public final class IngestionService {

    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

    private final IngestionStore store;
    private final long maxBytes;

    public IngestionService(IngestionStore store, long maxBytes) {
        this.store = Objects.requireNonNull(store, "store");
        if (maxBytes < 1) {
            throw new IllegalArgumentException("Maximum file size must be positive");
        }
        this.maxBytes = maxBytes;
    }

    /** A bank statement as CSV or camt.053. */
    public IngestionResult ingestStatement(byte[] content, FileFormat format) {
        Objects.requireNonNull(format, "format");
        if (format == FileFormat.JSON) {
            throw new IllegalArgumentException("JSON is not a statement format");
        }
        return ingest(LineSource.STATEMENT, format, content);
    }

    /** Ledger entries as CSV. */
    public IngestionResult ingestLedgerCsv(byte[] content) {
        return ingest(LineSource.LEDGER, FileFormat.CSV, content);
    }

    private IngestionResult ingest(LineSource kind, FileFormat format, byte[] content) {
        Objects.requireNonNull(content, "content");
        if (content.length > maxBytes) {
            throw new FileRejectedException(FileErrorCode.FILE_TOO_LARGE,
                    "The file exceeds the maximum size of " + maxBytes + " bytes");
        }
        ParsedFile parsed = format == FileFormat.CAMT053
                ? Camt053Parser.parse(content)
                : CsvParser.parse(content, kind);
        ContentHash hash = ContentHash.of(content);

        List<AcceptedLine> accepted = new ArrayList<>();
        List<RejectedLine> rejected = new ArrayList<>(parsed.rejected());
        for (ParsedRow row : parsed.rows()) {
            try {
                accepted.add(new AcceptedLine(row.lineNumber(),
                        LineNormaliser.normalise(kind, hash, row.lineNumber(), row.raw())));
            } catch (LineValidationException e) {
                rejected.add(new RejectedLine(row.lineNumber(), e.code(), e.getMessage(), row.rawText()));
            }
        }

        IngestionOutcome outcome =
                store.store(new IngestionRequest(kind, hash, format, content.length, accepted, rejected));
        return new IngestionResult(outcome, parsed.ignoredElements());
    }
}