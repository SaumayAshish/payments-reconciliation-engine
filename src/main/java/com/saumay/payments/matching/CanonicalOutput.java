package com.saumay.payments.matching;

import com.saumay.payments.domain.model.ContentHash;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The canonical text of a run's output and its SHA-256 (FRD-10, ADR-0003). One record per line, fields in a fixed
 * order separated by {@code |}, UTF-8, {@code \n} line ends. Records are sorted by a fixed key inside each section,
 * so the text depends on what was decided and not on the order it was found in. Times and run identifiers never
 * appear, so replaying a run on another day gives the same bytes.
 *
 * <pre>
 * CANONICAL|1
 * MATCH|tier|scoreMilli|band|statement keys|ledger keys|reason
 * EXCEPTION|kind|subjects (S: or L: prefix)|reason|detail|candidate keys
 * CARRIED|ledger key
 * NOTE|text
 * </pre>
 *
 * Matches are sorted by (statement keys, tier, ledger keys) and exceptions by (kind, first subject), as FRD-10
 * requires. Key lists inside a record are sorted.
 */
public final class CanonicalOutput {

    public static final int FORMAT_VERSION = 1;

    private record Record(String sortKey, String text) {
    }

    private static final Comparator<Record> ORDER = Comparator.comparing(Record::sortKey)
            .thenComparing(Record::text);

    private CanonicalOutput() {
    }

    public static String text(PipelineResult result, ExceptionClassifier.Classification classification) {
        StringBuilder text = new StringBuilder("CANONICAL|").append(FORMAT_VERSION).append('\n');

        List<Record> matches = new ArrayList<>();
        for (Match match : result.matches()) {
            String statements = joinKeys(match.statementSide());
            String ledger = joinKeys(match.ledgerSide());
            matches.add(new Record(statements + "|" + match.tier().number() + "|" + ledger,
                    "MATCH|" + match.tier() + "|" + match.score().milli() + "|" + match.band() + "|" + statements
                            + "|" + ledger + "|" + match.reason()));
        }
        append(text, matches);

        List<Record> exceptions = new ArrayList<>();
        for (RunException exception : classification.exceptions()) {
            List<String> subjects = new ArrayList<>();
            for (MatchableLine line : exception.subjects()) {
                subjects.add(ExceptionClassifier.sideCode(line) + ":" + line.key().value());
            }
            exceptions.add(new Record(exception.kind() + "|" + exception.subjects().get(0).key().value(),
                    "EXCEPTION|" + exception.kind() + "|" + String.join(",", subjects) + "|" + exception.reason()
                            + "|" + exception.detail() + "|" + joinKeys(exception.candidates())));
        }
        append(text, exceptions);

        List<Record> carried = new ArrayList<>();
        for (MatchableLine line : classification.carriedForward()) {
            carried.add(new Record(line.key().value(), "CARRIED|" + line.key().value()));
        }
        append(text, carried);

        List<Record> notes = new ArrayList<>();
        for (String note : result.notes()) {
            notes.add(new Record(note, "NOTE|" + note));
        }
        append(text, notes);
        return text.toString();
    }

    public static ContentHash hash(String canonicalText) {
        return ContentHash.ofUtf8(canonicalText);
    }

    private static void append(StringBuilder text, List<Record> records) {
        List<Record> sorted = new ArrayList<>(records);
        sorted.sort(ORDER);
        for (Record record : sorted) {
            text.append(record.text()).append('\n');
        }
    }

    private static String joinKeys(List<MatchableLine> lines) {
        List<String> keys = new ArrayList<>();
        for (MatchableLine line : lines) {
            keys.add(line.key().value());
        }
        Collections.sort(keys);
        return String.join(",", keys);
    }
}