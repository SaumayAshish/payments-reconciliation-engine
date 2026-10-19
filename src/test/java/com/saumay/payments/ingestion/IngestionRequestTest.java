package com.saumay.payments.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.ingestion.normalise.LineErrorCode;
import com.saumay.payments.ingestion.normalise.LineNormaliser;
import com.saumay.payments.ingestion.normalise.RawLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngestionRequestTest {

    private static final ContentHash HASH = ContentHash.ofUtf8("file");

    private static AcceptedLine accepted(LineSource source, ContentHash hash, int number) {
        RawLine raw = new RawLine("ACC-1", null, "2026-10-09", "10.00", "GBP", "REF-" + number, "", "");
        return new AcceptedLine(number, LineNormaliser.normalise(source, hash, number, raw));
    }

    private static RejectedLine rejected(int number) {
        return new RejectedLine(number, LineErrorCode.INVALID_AMOUNT, "amount is not a plain decimal number", "abc");
    }

    private static IngestionRequest statement(List<AcceptedLine> accepted, List<RejectedLine> rejected) {
        return new IngestionRequest(LineSource.STATEMENT, HASH, FileFormat.CSV, 10, accepted, rejected);
    }

    @Test
    void sortsLinesByLineNumberSoStorageOrderIsDeterministic() {
        IngestionRequest request = statement(
                List.of(accepted(LineSource.STATEMENT, HASH, 3), accepted(LineSource.STATEMENT, HASH, 1)),
                List.of(rejected(4), rejected(2)));
        assertEquals(List.of(1, 3), request.accepted().stream().map(AcceptedLine::lineNumber).toList());
        assertEquals(List.of(2, 4), request.rejected().stream().map(RejectedLine::lineNumber).toList());
    }

    @Test
    void rejectsALineFromTheOtherSide() {
        assertThrows(IllegalArgumentException.class,
                () -> statement(List.of(accepted(LineSource.LEDGER, HASH, 1)), List.of()));
    }

    @Test
    void rejectsALineWhoseKeyDoesNotBelongToThisFile() {
        ContentHash other = ContentHash.ofUtf8("another file");
        assertThrows(IllegalArgumentException.class,
                () -> statement(List.of(accepted(LineSource.STATEMENT, other, 1)), List.of()));
    }

    @Test
    void rejectsTheSameLineNumberUsedTwice() {
        assertThrows(IllegalArgumentException.class, () -> statement(
                List.of(accepted(LineSource.STATEMENT, HASH, 1), accepted(LineSource.STATEMENT, HASH, 1)),
                List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> statement(List.of(accepted(LineSource.STATEMENT, HASH, 1)), List.of(rejected(1))));
    }

    @Test
    void rejectsANegativeByteSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new IngestionRequest(LineSource.STATEMENT, HASH, FileFormat.CSV, -1, List.of(), List.of()));
    }

    @Test
    void rejectedLinesTruncateLongExcerptsAndRejectBadLineNumbers() {
        assertEquals(RejectedLine.MAX_EXCERPT_LENGTH,
                new RejectedLine(1, LineErrorCode.INVALID_DATE, "m", "x".repeat(500)).rawExcerpt().length());
        assertEquals("", new RejectedLine(1, LineErrorCode.INVALID_DATE, "m", null).rawExcerpt());
        assertThrows(IllegalArgumentException.class,
                () -> new RejectedLine(0, LineErrorCode.INVALID_DATE, "m", "x"));
    }
}