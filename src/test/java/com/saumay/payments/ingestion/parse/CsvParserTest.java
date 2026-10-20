package com.saumay.payments.ingestion.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.ingestion.RejectedLine;
import com.saumay.payments.ingestion.normalise.LineErrorCode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CsvParserTest {

    private static final String HEADER =
            "account,booking_date,value_date,amount,currency,reference,counterparty,narrative";
    private static final String ROW1 = "ACC-1,,2026-10-09,10.00,GBP,REF-1,Acme Ltd,payment";
    private static final String ROW2 = "ACC-1,2026-10-10,2026-10-10,-5.50,GBP,REF-2,Beta Ltd,refund";

    @Test
    void validRowsKeepTheirFieldsAndAreNumberedFromOne() {
        ParsedFile file = parse(LineSource.STATEMENT, HEADER, ROW1, ROW2);

        assertEquals(2, file.rows().size());
        assertEquals(0, file.rejected().size());
        assertEquals(1, file.rows().get(0).lineNumber());
        assertEquals("ACC-1", file.rows().get(0).raw().account());
        assertEquals("", file.rows().get(0).raw().bookingDate());
        assertEquals("10.00", file.rows().get(0).raw().amount());
        assertEquals(2, file.rows().get(1).lineNumber());
        assertEquals("-5.50", file.rows().get(1).raw().amount());
        assertEquals(ROW1, file.rows().get(0).rawText());
    }

    @Test
    void quotedFieldsMayContainCommasQuotesAndLineBreaks() {
        String row = "ACC-1,,2026-10-09,10.00,GBP,\"REF,1\",\"Acme \"\"Big\"\" Ltd\",\"line one\nline two\"";
        ParsedFile file = parse(LineSource.STATEMENT, HEADER, row, ROW2);

        assertEquals(2, file.rows().size());
        assertEquals("REF,1", file.rows().get(0).raw().reference());
        assertEquals("Acme \"Big\" Ltd", file.rows().get(0).raw().counterparty());
        assertEquals("line one\nline two", file.rows().get(0).raw().narrative());
        assertEquals(2, file.rows().get(1).lineNumber());
    }

    @Test
    void headerOrderAndCaseAreFlexibleAndABomAndCrLfAreAccepted() {
        String text = "\uFEFFNarrative,Reference,Counterparty,Currency,Amount,Value_Date,Booking_Date,Account\r\n"
                + "payment,REF-1,Acme Ltd,GBP,10.00,2026-10-09,,ACC-1\r\n";
        ParsedFile file = CsvParser.parse(text.getBytes(StandardCharsets.UTF_8), LineSource.STATEMENT);

        assertEquals(1, file.rows().size());
        assertEquals("ACC-1", file.rows().get(0).raw().account());
        assertEquals("REF-1", file.rows().get(0).raw().reference());
        assertEquals("2026-10-09", file.rows().get(0).raw().valueDate());
    }

    @Test
    void aRowWithTheWrongColumnCountIsRejectedAndTheOthersKeepTheirNumbers() {
        ParsedFile file = parse(LineSource.STATEMENT, HEADER, ROW1, "ACC-1,only,three", ROW2);

        assertEquals(2, file.rows().size());
        assertEquals(1, file.rows().get(0).lineNumber());
        assertEquals(3, file.rows().get(1).lineNumber());
        assertEquals(1, file.rejected().size());
        RejectedLine bad = file.rejected().get(0);
        assertEquals(2, bad.lineNumber());
        assertEquals(LineErrorCode.MALFORMED_ROW, bad.code());
        assertEquals("ACC-1,only,three", bad.rawExcerpt());
    }

    @Test
    void aStrayQuoteRejectsOnlyThatRow() {
        ParsedFile file = parse(LineSource.STATEMENT, HEADER,
                "ACC-1,,2026-10-09,10.00,GBP,RE\"F-1,Acme,payment", ROW2);

        assertEquals(1, file.rows().size());
        assertEquals(1, file.rejected().size());
        assertEquals(1, file.rejected().get(0).lineNumber());
        assertEquals(LineErrorCode.MALFORMED_ROW, file.rejected().get(0).code());
    }

    @Test
    void textAfterAClosingQuoteRejectsThatRow() {
        ParsedFile file = parse(LineSource.STATEMENT, HEADER,
                "ACC-1,,2026-10-09,10.00,GBP,\"REF-1\"x,Acme,payment");

        assertEquals(0, file.rows().size());
        assertEquals(1, file.rejected().size());
    }

    @Test
    void blankLinesAreSkippedAndDoNotConsumeLineNumbers() {
        ParsedFile file = parse(LineSource.STATEMENT, HEADER, "", ROW1, "", "", ROW2, "");

        assertEquals(2, file.rows().size());
        assertEquals(1, file.rows().get(0).lineNumber());
        assertEquals(2, file.rows().get(1).lineNumber());
    }

    @Test
    void ledgerFilesMayCarryAnEntryIdAndBlankMeansAbsent() {
        String header = HEADER + ",entry_id";
        ParsedFile file = parse(LineSource.LEDGER, header, ROW1 + ",L-100", ROW2 + ",");

        assertEquals("L-100", file.rows().get(0).entryId());
        assertNull(file.rows().get(1).entryId());
    }

    @Test
    void statementFilesDoNotAcceptAnEntryIdColumn() {
        assertFileError(FileErrorCode.INVALID_HEADER,
                () -> parse(LineSource.STATEMENT, HEADER + ",entry_id", ROW1 + ",L-1"));
    }

    @Test
    void emptyAndHeaderOnlyFilesAreRefused() {
        assertFileError(FileErrorCode.EMPTY_FILE, () -> CsvParser.parse(new byte[0], LineSource.STATEMENT));
        assertFileError(FileErrorCode.EMPTY_FILE, () -> parse(LineSource.STATEMENT, "", ""));
        assertFileError(FileErrorCode.NO_DATA_ROWS, () -> parse(LineSource.STATEMENT, HEADER));
    }

    @Test
    void invalidUtf8IsRefusedNotGuessed() {
        byte[] bytes = {(byte) 0xC3, (byte) 0x28, '\n'};
        assertFileError(FileErrorCode.UNSUPPORTED_ENCODING, () -> CsvParser.parse(bytes, LineSource.STATEMENT));
    }

    @Test
    void badHeadersAreRefused() {
        assertFileError(FileErrorCode.INVALID_HEADER,
                () -> parse(LineSource.STATEMENT, "account,amount", "ACC-1,10.00"));
        assertFileError(FileErrorCode.INVALID_HEADER,
                () -> parse(LineSource.STATEMENT, HEADER + ",surprise", ROW1 + ",x"));
        assertFileError(FileErrorCode.INVALID_HEADER,
                () -> parse(LineSource.STATEMENT, HEADER + ",amount", ROW1 + ",1"));
    }

    @Test
    void anUnterminatedQuoteRefusesTheWholeFile() {
        assertFileError(FileErrorCode.MALFORMED_CSV, () -> parse(LineSource.STATEMENT, HEADER,
                "ACC-1,,2026-10-09,10.00,GBP,\"REF-1,Acme,payment", ROW2));
    }

    @Test
    void sameBytesAlwaysGiveTheSameResult() {
        byte[] bytes = String.join("\n", HEADER, ROW1, "ACC-1,bad", ROW2).getBytes(StandardCharsets.UTF_8);

        assertEquals(CsvParser.parse(bytes, LineSource.STATEMENT), CsvParser.parse(bytes, LineSource.STATEMENT));
    }

    // ---- helpers ----

    private static ParsedFile parse(LineSource kind, String... lines) {
        byte[] bytes = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
        return CsvParser.parse(bytes, kind);
    }

    private static void assertFileError(FileErrorCode expected, Runnable action) {
        FileRejectedException e = assertThrows(FileRejectedException.class, action::run);
        assertEquals(expected, e.code());
    }
}