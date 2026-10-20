package com.saumay.payments.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.ingestion.normalise.LineErrorCode;
import com.saumay.payments.ingestion.normalise.LineNormaliser;
import com.saumay.payments.ingestion.normalise.RawLine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LedgerEntryIdResolverTest {

    private static final ContentHash HASH = ContentHash.ofUtf8("ledger-file");

    /** Same reference for every line, so lines differ in content only by amount. */
    private static AcceptedLine line(int number, String entryId, String amount) {
        RawLine raw = new RawLine("ACC-1", null, "2026-10-09", amount, "GBP", "REF-1", "Acme", "pay");
        return new AcceptedLine(number, LineNormaliser.normalise(LineSource.LEDGER, HASH, number, raw), entryId);
    }

    @Test
    void newEntryIdsAndLinesWithoutOneAreAllKept() {
        List<AcceptedLine> lines = List.of(line(1, "L-1", "10.00"), line(2, "L-2", "10.00"), line(3, null, "10.00"));

        LedgerEntryIdResolver.Resolution result = LedgerEntryIdResolver.resolve(lines, List.of(), Map.of());

        assertEquals(3, result.accepted().size());
        assertEquals(0, result.rejected().size());
    }

    @Test
    void aKnownIdWithIdenticalContentIsADuplicateEntry() {
        AcceptedLine stored = line(1, "L-1", "10.00");
        Map<String, EntryContent> known = Map.of("L-1", EntryContent.of(stored.line()));

        LedgerEntryIdResolver.Resolution result =
                LedgerEntryIdResolver.resolve(List.of(line(1, "L-1", "10.00")), List.of(), known);

        assertEquals(0, result.accepted().size());
        assertEquals(LineErrorCode.DUPLICATE_LEDGER_ENTRY, result.rejected().get(0).code());
    }

    @Test
    void aKnownIdWithDifferentContentIsAConflict() {
        Map<String, EntryContent> known = Map.of("L-1", EntryContent.of(line(1, "L-1", "10.00").line()));

        LedgerEntryIdResolver.Resolution result =
                LedgerEntryIdResolver.resolve(List.of(line(1, "L-1", "99.00")), List.of(), known);

        assertEquals(0, result.accepted().size());
        assertEquals(LineErrorCode.LEDGER_ENTRY_CONFLICT, result.rejected().get(0).code());
    }

    @Test
    void insideOneFileTheFirstOccurrenceWins() {
        List<AcceptedLine> lines = List.of(
                line(1, "L-1", "10.00"), line(2, "L-1", "10.00"), line(3, "L-1", "99.00"));

        LedgerEntryIdResolver.Resolution result = LedgerEntryIdResolver.resolve(lines, List.of(), Map.of());

        assertEquals(1, result.accepted().size());
        assertEquals(1, result.accepted().get(0).lineNumber());
        assertEquals(2, result.rejected().get(0).lineNumber());
        assertEquals(LineErrorCode.DUPLICATE_LEDGER_ENTRY, result.rejected().get(0).code());
        assertEquals(3, result.rejected().get(1).lineNumber());
        assertEquals(LineErrorCode.LEDGER_ENTRY_CONFLICT, result.rejected().get(1).code());
    }

    @Test
    void storedContentWinsEvenWhenTheIdIsFirstInTheFile() {
        Map<String, EntryContent> known = Map.of("L-1", EntryContent.of(line(1, "L-1", "10.00").line()));

        LedgerEntryIdResolver.Resolution result =
                LedgerEntryIdResolver.resolve(List.of(line(1, "L-1", "99.00")), List.of(), known);

        assertEquals(LineErrorCode.LEDGER_ENTRY_CONFLICT, result.rejected().get(0).code());
    }

    @Test
    void earlierRejectionsAreMergedInLineOrder() {
        List<AcceptedLine> lines = List.of(line(1, "L-1", "10.00"), line(3, "L-1", "99.00"));
        List<RejectedLine> earlier =
                List.of(new RejectedLine(2, LineErrorCode.INVALID_AMOUNT, "bad amount", "abc"));

        LedgerEntryIdResolver.Resolution result = LedgerEntryIdResolver.resolve(lines, earlier, Map.of());

        assertEquals(List.of(2, 3), result.rejected().stream().map(RejectedLine::lineNumber).toList());
    }

    @Test
    void theSameInputAlwaysGivesTheSameResolution() {
        List<AcceptedLine> lines = List.of(
                line(1, "L-1", "10.00"), line(2, "L-1", "99.00"), line(3, "L-2", "10.00"));

        assertEquals(LedgerEntryIdResolver.resolve(lines, List.of(), Map.of()),
                LedgerEntryIdResolver.resolve(lines, List.of(), Map.of()));
    }
}