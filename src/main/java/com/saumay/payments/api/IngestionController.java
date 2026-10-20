package com.saumay.payments.api;

import com.saumay.payments.ingestion.FileFormat;
import com.saumay.payments.ingestion.IngestionOutcome;
import com.saumay.payments.ingestion.IngestionReader;
import com.saumay.payments.ingestion.parse.FileErrorCode;
import com.saumay.payments.ingestion.parse.FileRejectedException;
import com.saumay.payments.ingestion.service.IngestionResult;
import com.saumay.payments.ingestion.service.IngestionService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Upload and read back ingestions. Files arrive as the raw request body, so the hash covers exactly the bytes sent.
 * The body is read up to the size limit plus one byte, so an oversized upload is refused without being buffered.
 */
@RestController
@RequestMapping("/api/v1/ingestions")
public class IngestionController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv");

    private final IngestionService service;
    private final IngestionReader reader;

    public IngestionController(IngestionService service, IngestionReader reader) {
        this.service = service;
        this.reader = reader;
    }

    @PostMapping("/statements")
    public ResponseEntity<IngestionDtos.Created> uploadStatement(HttpServletRequest request) throws IOException {
        FileFormat format = statementFormat(request.getContentType());
        byte[] body = readBounded(request);
        return respond(() -> service.ingestStatement(body, format));
    }

    @PostMapping("/ledger")
    public ResponseEntity<IngestionDtos.Created> uploadLedger(HttpServletRequest request) throws IOException {
        if (!isType(request.getContentType(), CSV)) {
            throw unsupportedMediaType();
        }
        byte[] body = readBounded(request);
        return respond(() -> service.ingestLedgerCsv(body));
    }

    @GetMapping("/{id}")
    public IngestionDtos.Detail get(@PathVariable("id") long id) {
        return reader.find(id)
                .map(IngestionDtos.Detail::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INGESTION_NOT_FOUND",
                        "No ingestion exists with that id"));
    }

    private ResponseEntity<IngestionDtos.Created> respond(Supplier<IngestionResult> action) {
        IngestionResult result;
        try {
            result = action.get();
        } catch (FileRejectedException e) {
            int status = e.code() == FileErrorCode.FILE_TOO_LARGE ? 413 : 422;
            throw new ApiException(HttpStatus.valueOf(status), "INGESTION_" + e.code().name(), e.getMessage());
        }
        IngestionOutcome outcome = result.outcome();
        IngestionDtos.Created body = new IngestionDtos.Created(outcome.ingestionId(), outcome.acceptedCount(),
                outcome.rejectedCount(), outcome.duplicate(), result.ignoredElements());
        if (outcome.duplicate()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/ingestions/" + outcome.ingestionId())).body(body);
    }

    private byte[] readBounded(HttpServletRequest request) throws IOException {
        int cap = (int) Math.min(service.maxBytes() + 1, Integer.MAX_VALUE - 8);
        return request.getInputStream().readNBytes(cap);
    }

    private static FileFormat statementFormat(String contentType) {
        if (isType(contentType, CSV)) {
            return FileFormat.CSV;
        }
        if (isType(contentType, MediaType.APPLICATION_XML) || isType(contentType, MediaType.TEXT_XML)) {
            return FileFormat.CAMT053;
        }
        throw unsupportedMediaType();
    }

    private static boolean isType(String contentType, MediaType expected) {
        if (contentType == null) {
            return false;
        }
        try {
            return MediaType.parseMediaType(contentType).equalsTypeAndSubtype(expected);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static ApiException unsupportedMediaType() {
        return new ApiException(HttpStatus.valueOf(415), "INGESTION_UNSUPPORTED_MEDIA_TYPE",
                "Content-Type must be text/csv, or application/xml for camt.053 statements");
    }
}