package com.saumay.payments.ingestion;

import com.saumay.payments.ingestion.normalise.LineErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies the entry id rules (FRD AC-03.4). An entry id is unique across all ledger files: the first sight is
 * kept, an identical repeat is rejected as a duplicate entry, different content under the same id is rejected as a
 * conflict. Entries already stored win over the incoming file; within a file the first occurrence wins.
 *
 * <p>A pure function: lists are processed in line order and the maps are only ever looked up, never iterated.
 */
public final class LedgerEntryIdResolver {

    private LedgerEntryIdResolver() {
    }

    public record Resolution(List<AcceptedLine> accepted, List<RejectedLine> rejected) {
    }

    /**
     * @param accepted lines in line-number order
     * @param known    content of entries already stored, by entry id
     */
    public static Resolution resolve(List<AcceptedLine> accepted, List<RejectedLine> rejected,
                                     Map<String, EntryContent> known) {
        Map<String, EntryContent> seen = new HashMap<>(known);
        List<AcceptedLine> kept = new ArrayList<>();
        List<RejectedLine> all = new ArrayList<>(rejected);

        for (AcceptedLine line : accepted) {
            String id = line.entryId();
            if (id == null) {
                kept.add(line);
                continue;
            }
            EntryContent content = EntryContent.of(line.line());
            EntryContent previous = seen.putIfAbsent(id, content);
            if (previous == null) {
                kept.add(line);
            } else if (previous.equals(content)) {
                all.add(new RejectedLine(line.lineNumber(), LineErrorCode.DUPLICATE_LEDGER_ENTRY,
                        "entry_id was already ingested with identical content", "entry_id=" + id));
            } else {
                all.add(new RejectedLine(line.lineNumber(), LineErrorCode.LEDGER_ENTRY_CONFLICT,
                        "entry_id was already used with different content", "entry_id=" + id));
            }
        }
        all.sort(Comparator.comparingInt(RejectedLine::lineNumber));
        return new Resolution(kept, all);
    }
}