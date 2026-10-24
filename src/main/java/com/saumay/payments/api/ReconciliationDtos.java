package com.saumay.payments.api;

import com.saumay.payments.application.ReplayResult;
import com.saumay.payments.application.RunHeader;
import com.saumay.payments.application.RunResultReader;
import com.saumay.payments.matching.MatchingConfig;
import com.saumay.payments.matching.RunSummary;
import java.time.LocalDate;
import java.util.List;

/**
 * The wire shapes of the reconciliation API. Entity and domain types never cross the boundary: dates and instants
 * go out as ISO-8601 strings and money as integer minor units with its currency code.
 */
public final class ReconciliationDtos {

    private ReconciliationDtos() {
    }

    /** Any field left out takes the BRD default; an out-of-range value is refused by the configuration itself. */
    public record ConfigOverride(Integer autoThresholdMilli, Integer reviewThresholdMilli,
            Integer dateWindowBusinessDays, Integer lookbackBusinessDays, Integer maxOneToManyCandidates,
            Integer fuzzyMinSimilarityMilli, Integer fuzzyCounterpartyWeightMilli) {
    }

    public record StartRequest(LocalDate businessDate, List<Long> statementIngestionIds, ConfigOverride config) {
    }

    public record ConfigDto(int autoThresholdMilli, int reviewThresholdMilli, int dateWindowBusinessDays,
            int lookbackBusinessDays, int maxOneToManyCandidates, int fuzzyMinSimilarityMilli,
            int fuzzyCounterpartyWeightMilli) {

        static ConfigDto from(MatchingConfig c) {
            return new ConfigDto(c.autoThresholdMilli(), c.reviewThresholdMilli(), c.dateWindowBusinessDays(),
                    c.lookbackBusinessDays(), c.maxOneToManyCandidates(), c.fuzzyMinSimilarityMilli(),
                    c.fuzzyCounterpartyWeightMilli());
        }
    }

    public record SummaryDto(int statementLines, int ledgerEntries, int matches, int autoConfirmedMatches,
            int reviewMatches, int ambiguousGroups, int exceptions, int carriedForward, int truncationNotes) {

        static SummaryDto from(RunSummary s) {
            return s == null ? null : new SummaryDto(s.statementLines(), s.ledgerEntries(), s.matches(),
                    s.autoConfirmedMatches(), s.reviewMatches(), s.ambiguousGroups(), s.exceptions(),
                    s.carriedForward(), s.truncationNotes());
        }
    }

    public record RunDetail(long id, String status, String businessDate, List<Long> statementIngestionIds,
            long ledgerCutoffSeq, ConfigDto config, String createdAt, String startedAt, String completedAt,
            Long durationMillis, String outputHash, SummaryDto summary, String failureReason) {

        static RunDetail from(RunHeader r) {
            return new RunDetail(r.id(), r.status().name(), r.businessDate().toString(), r.statementIngestionIds(),
                    r.ledgerCutoffSeq(), ConfigDto.from(r.config()), text(r.createdAt()), text(r.startedAt()),
                    text(r.completedAt()), r.durationMillis(), r.outputHash(), SummaryDto.from(r.summary()),
                    r.failureReason());
        }

        private static String text(java.time.Instant instant) {
            return instant == null ? null : instant.toString();
        }
    }

    public record ReplayDto(long runId, String storedHash, String replayedHash, boolean identical) {

        static ReplayDto from(ReplayResult r) {
            return new ReplayDto(r.runId(), r.storedHash(), r.replayedHash(), r.identical());
        }
    }

    public record LineDto(String source, long seq, String reference, long amountMinor, String currency,
            String valueDate) {

        static LineDto from(RunResultReader.LineView l) {
            return new LineDto(l.source().name(), l.seq(), l.reference(), l.amountMinor(), l.currency(),
                    l.valueDate() == null ? null : l.valueDate().toString());
        }
    }

    public record MatchDto(int ordinal, String tier, int scoreMilli, String band, String reason,
            List<LineDto> lines) {

        static MatchDto from(RunResultReader.MatchView m) {
            return new MatchDto(m.ordinal(), m.tier(), m.scoreMilli(), m.band(), m.reason(),
                    m.lines().stream().map(LineDto::from).toList());
        }
    }

    public record ExceptionDto(int ordinal, String kind, String reason, String detail, List<LineDto> subjects,
            List<LineDto> candidates) {

        static ExceptionDto from(RunResultReader.ExceptionView e) {
            return new ExceptionDto(e.ordinal(), e.kind(), e.reason(), e.detail(),
                    e.subjects().stream().map(LineDto::from).toList(),
                    e.candidates().stream().map(LineDto::from).toList());
        }
    }

    /** One page of results. {@code nextAfter} is the cursor for the following page, or null on the last page. */
    public record Page<T>(long runId, String view, List<T> items, Long nextAfter) {
    }
}