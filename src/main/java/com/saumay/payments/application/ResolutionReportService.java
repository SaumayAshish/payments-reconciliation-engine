package com.saumay.payments.application;

import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CaseTeam;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** Deterministic elapsed resolution-time aggregates for a current snapshot. */
public final class ResolutionReportService {

    private final ResolutionReportReader reader;

    public ResolutionReportService(ResolutionReportReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public Report report() {
        var groups = new TreeMap<Key, Counts>(
                Comparator.comparing((Key key) -> key.team().name())
                        .thenComparing(key -> key.kind().name()));
        long total = 0;

        for (ResolutionReportReader.ResolvedCase row : reader.resolvedCases()) {
            Counts counts = groups.computeIfAbsent(new Key(row.team(), row.kind()), key -> new Counts());
            counts.add(row);
            total = Math.addExact(total, 1);
        }

        List<Group> result = new ArrayList<>();
        groups.forEach((key, counts) -> result.add(counts.toGroup(key)));
        return new Report(total, List.copyOf(result));
    }

    public record Report(long resolvedCount, List<Group> groups) {
        public Report {
            groups = List.copyOf(groups);
        }
    }

    public record Group(CaseTeam team, CaseKind kind, long resolvedCount, long measuredCount,
            long invalidTimeOrderCount, long totalSeconds, Long minimumSeconds, Long maximumSeconds) {
    }

    private record Key(CaseTeam team, CaseKind kind) {
    }

    private static final class Counts {
        private long resolved;
        private long measured;
        private long invalid;
        private long totalSeconds;
        private Long minimum;
        private Long maximum;

        void add(ResolutionReportReader.ResolvedCase row) {
            resolved = Math.addExact(resolved, 1);
            if (row.resolvedAt().isBefore(row.createdAt())) {
                invalid = Math.addExact(invalid, 1);
                return;
            }
            long seconds = Duration.between(row.createdAt(), row.resolvedAt()).getSeconds();
            measured = Math.addExact(measured, 1);
            totalSeconds = Math.addExact(totalSeconds, seconds);
            minimum = minimum == null ? seconds : Math.min(minimum, seconds);
            maximum = maximum == null ? seconds : Math.max(maximum, seconds);
        }

        Group toGroup(Key key) {
            return new Group(key.team(), key.kind(), resolved, measured, invalid,
                    totalSeconds, minimum, maximum);
        }
    }
}