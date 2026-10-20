package com.saumay.payments.ingestion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.ingestion.FileFormat;
import com.saumay.payments.ingestion.IngestionOutcome;
import com.saumay.payments.ingestion.IngestionRequest;
import com.saumay.payments.ingestion.IngestionStore;
import com.saumay.payments.ingestion.normalise.LineErrorCode;
import com.saumay.payments.ingestion.parse.FileErrorCode;
import com.saumay.payments.ingestion.parse.FileRejectedException;
import com.saumay.payments.ingestion.parse.LedgerJsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngestionServiceTest {

    private static final String HEADER =
            "account,booking_date,value_date,amount,currency,reference,counterparty,narrative";
    private static final String GOOD = "ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd,payment";

    private final RecordingStore store = new RecordingStore();
    private final IngestionService service = new IngestionService(store, IngestionService.DEFAULT_MAX_BYTES);

    @Test
    void validRowsAreStoredAndInvalidRowsKeepTheirReasons() {
        byte[] bytes = csv(HEADER,
                GOOD,
                "ACC-1,,2026-10-09,10.00,XX1,REF-2,Acme Ltd,payment",
                "ACC-1,,2026-10-09,10.005,GBP,REF-3,Acme Ltd,payment");

        IngestionResult result = service.ingestStatement(bytes, FileFormat.CSV);

        IngestionRequest request = store.onlyRequest();
        assertEquals(LineSource.STATEMENT, request.kind());
        assertEquals(FileFormat.CSV, request.format());
        assertEquals(ContentHash.of(bytes), request.fileHash());
        assertEquals(bytes.length, request.byteSize());
        assertEquals(1, request.accepted().size());
        assertEquals(1, request.accepted().get(0).lineNumber());
        assertEquals(2, request.rejected().size());
        assertEquals(2, request.rejected().get(0).lineNumber());
        assertEquals(LineErrorCode.INVALID_CURRENCY, request.rejected().get(0).code());
        assertEquals(3, request.rejected().get(1).lineNumber());
        assertEquals(LineErrorCode.AMOUNT_PRECISION, request.rejected().get(1).code());
        assertEquals(1, result.outcome().acceptedCount());
        assertEquals(2, result.outcome().rejectedCount());
    }

    @Test
    void structuralAndValidationRejectsShareOneLineNumbering() {
        byte[] bytes = csv(HEADER,
                GOOD,
                "ACC-1,only,three",
                "ACC-1,,2026-10-09,abc,GBP,REF-3,Acme Ltd,payment");

        service.ingestStatement(bytes, FileFormat.CSV);

        IngestionRequest request = store.onlyRequest();
        assertEquals(1, request.accepted().size());
        assertEquals(2, request.rejected().get(0).lineNumber());
        assertEquals(LineErrorCode.MALFORMED_ROW, request.rejected().get(0).code());
        assertEquals(3, request.rejected().get(1).lineNumber());
        assertEquals(LineErrorCode.INVALID_AMOUNT, request.rejected().get(1).code());
    }

    @Test
    void aCamtStatementIsParsedAndNormalisedLikeACsvOne() {
        String xml = "<Document><Stmt><Acct><Id><IBAN>GB00SYNTH0001</IBAN></Id></Acct><Ntry>"
                + "<Amt Ccy=\"GBP\">1250.50</Amt><CdtDbtInd>CRDT</CdtDbtInd>"
                + "<ValDt><Dt>2026-10-10</Dt></ValDt>"
                + "<NtryDtls><TxDtls><Refs><EndToEndId>E2E-1</EndToEndId></Refs></TxDtls></NtryDtls>"
                + "<AcctSvcrRef>ignored</AcctSvcrRef></Ntry></Stmt></Document>";

        IngestionResult result = service.ingestStatement(xml.getBytes(StandardCharsets.UTF_8), FileFormat.CAMT053);

        IngestionRequest request = store.onlyRequest();
        assertEquals(FileFormat.CAMT053, request.format());
        assertEquals(1, request.accepted().size());
        assertEquals(125050L, request.accepted().get(0).line().amount().minorUnits());
        assertEquals(1, result.ignoredElements());
    }

    @Test
    void ledgerFilesAreStoredOnTheLedgerSide() {
        service.ingestLedgerCsv(csv(HEADER, GOOD));

        IngestionRequest request = store.onlyRequest();
        assertEquals(LineSource.LEDGER, request.kind());
        assertEquals(LineSource.LEDGER, request.accepted().get(0).line().source());
    }

    @Test
    void anOversizedFileIsRefusedBeforeParsingAndNothingIsStored() {
        IngestionService small = new IngestionService(store, 10);

        FileRejectedException e = assertThrows(FileRejectedException.class,
                () -> small.ingestStatement(csv(HEADER, GOOD), FileFormat.CSV));

        assertEquals(FileErrorCode.FILE_TOO_LARGE, e.code());
        assertTrue(store.requests.isEmpty());
    }

    @Test
    void filesRefusedAsAWholeNeverReachTheStore() {
        assertThrows(FileRejectedException.class, () -> service.ingestStatement(csv(HEADER), FileFormat.CSV));
        assertThrows(FileRejectedException.class, () -> service.ingestStatement(new byte[0], FileFormat.CSV));

        assertTrue(store.requests.isEmpty());
    }

    @Test
    void sameBytesProduceIdenticalRequests() {
        byte[] bytes = csv(HEADER, GOOD, "ACC-1,only,three");

        service.ingestStatement(bytes, FileFormat.CSV);
        service.ingestStatement(bytes, FileFormat.CSV);

        assertEquals(2, store.requests.size());
        assertEquals(store.requests.get(0), store.requests.get(1));
    }

    @Test
    void jsonIsNotAStatementFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> service.ingestStatement(csv(HEADER, GOOD), FileFormat.JSON));
    }

    @Test
    void theStoresDuplicateVerdictIsPassedThroughUnchanged() {
        store.duplicate = true;

        IngestionResult result = service.ingestStatement(csv(HEADER, GOOD), FileFormat.CSV);

        assertTrue(result.outcome().duplicate());
    }
    @Test
    void ledgerJsonIsStoredAsJsonAndHashedOverItsCanonicalForm() {
        String compact = "[{\"account\":\"ACC-1\",\"value_date\":\"2026-10-09\",\"amount\":\"10.00\","
                + "\"currency\":\"GBP\",\"reference\":\"REF-1\"}]";
        String spaced = "[ { \"reference\": \"REF-1\", \"currency\": \"GBP\", \"amount\": \"10.00\","
                + " \"value_date\": \"2026-10-09\", \"account\": \"ACC-1\" } ]";

        service.ingestLedgerJson(compact.getBytes(StandardCharsets.UTF_8));
        service.ingestLedgerJson(spaced.getBytes(StandardCharsets.UTF_8));

        byte[] canonical = LedgerJsonParser.parse(compact.getBytes(StandardCharsets.UTF_8)).canonicalBytes();
        IngestionRequest first = store.requests.get(0);
        assertEquals(LineSource.LEDGER, first.kind());
        assertEquals(FileFormat.JSON, first.format());
        assertEquals(ContentHash.of(canonical), first.fileHash());
        assertEquals(canonical.length, first.byteSize());
        assertEquals(first.fileHash(), store.requests.get(1).fileHash());
    }

    @Test
    void ledgerJsonEntriesAreNormalisedAndInvalidOnesKeepTheirPosition() {
        String json = "[{\"account\":\"ACC-1\",\"value_date\":\"2026-10-09\",\"amount\":\"10.00\","
                + "\"currency\":\"GBP\",\"reference\":\"REF-1\"},"
                + "{\"account\":\"ACC-1\",\"value_date\":\"2026-10-09\",\"amount\":\"10.00\","
                + "\"currency\":\"XX1\",\"reference\":\"REF-2\"}]";

        service.ingestLedgerJson(json.getBytes(StandardCharsets.UTF_8));

        IngestionRequest request = store.onlyRequest();
        assertEquals(1, request.accepted().size());
        assertEquals(1, request.accepted().get(0).lineNumber());
        assertEquals(1, request.rejected().size());
        assertEquals(2, request.rejected().get(0).lineNumber());
        assertEquals(LineErrorCode.INVALID_CURRENCY, request.rejected().get(0).code());
    }

    @Test
    void anOversizedLedgerJsonBodyIsRefusedBeforeParsing() {
        IngestionService small = new IngestionService(store, 10);

        FileRejectedException e = assertThrows(FileRejectedException.class,
                () -> small.ingestLedgerJson("[{\"account\":\"ACC-1\"}]".getBytes(StandardCharsets.UTF_8)));

        assertEquals(FileErrorCode.FILE_TOO_LARGE, e.code());
        assertTrue(store.requests.isEmpty());
    }

    // ---- helpers ----

    private static byte[] csv(String... lines) {
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    private static final class RecordingStore implements IngestionStore {

        final List<IngestionRequest> requests = new ArrayList<>();
        boolean duplicate;

        @Override
        public IngestionOutcome store(IngestionRequest request) {
            requests.add(request);
            return new IngestionOutcome(1L, request.accepted().size(), request.rejected().size(), duplicate);
        }

        IngestionRequest onlyRequest() {
            assertEquals(1, requests.size());
            return requests.get(0);
        }
    }
}