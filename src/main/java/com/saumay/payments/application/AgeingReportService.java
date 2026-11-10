package com.saumay.payments.application;

import com.saumay.payments.domain.cases.CaseKind;
import com.saumay.payments.domain.cases.CaseTeam;
import com.saumay.payments.domain.cases.SlaPolicy;
import com.saumay.payments.domain.reporting.CaseAgeing;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** Deterministic ageing and SLA aggregates for a current case snapshot. */
public final class AgeingReportService {

    private final AgeingReportReader reader;

    public AgeingReportService(AgeingReportReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public Report report(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        var groups = new TreeMap<Key, Counts>(
                Comparator.comparing((Key key) -> key.team().name())
                        .thenComparing(key -> key.kind().name()));
        long total = 0;
        SlaPolicy policy = SlaPolicy.standard();

        for (AgeingReportReader.CaseRow row : reader.unresolvedCases()) {
            CaseAgeing ageing = CaseAgeing.evaluate(
                    row.createdBusinessDate(), row.slaBusinessDays(), asOf, policy);
            Counts counts = groups.computeIfAbsent(new Key(row.team(), row.kind()), key -> new Counts());
            counts.add(ageing);
            total = Math.addExact(total, 1);
        }

        List<Group> result = new ArrayList<>();
        groups.forEach((key, counts) -> result.add(counts.toGroup(key)));
        return new Report(asOf, total, List.copyOf(result));
    }

    public record Report(LocalDate asOf, long unresolvedCount, List<Group> groups) {
        public Report {
            Objects.requireNonNull(asOf, "asOf");
            groups = List.copyOf(groups);
        }
    }

    public record Group(CaseTeam team, CaseKind kind, long total,
            long zeroToOne, long twoToFive, long sixToTen, long overTen,
            long withinSla, long atRisk, long breached) {
    }

    private record Key(CaseTeam team, CaseKind kind) {
    }

    private static final class Counts {
        private long total;
        private long zeroToOne;
        private long twoToFive;
        private long sixToTen;
        private long overTen;
        private long withinSla;
        private long atRisk;
        private long breached;

        void add(CaseAgeing ageing) {
            total = Math.addExact(total, 1);
            switch (ageing.bucket()) {
                case ZERO_TO_ONE -> zeroToOne = Math.addExact(zeroToOne, 1);
                case TWO_TO_FIVE -> twoToFive = Math.addExact(twoToFive, 1);
                case SIX_TO_TEN -> sixToTen = Math.addExact(sixToTen, 1);
                case OVER_TEN -> overTen = Math.addExact(overTen, 1);
            }
            switch (ageing.slaStatus()) {
                case WITHIN_SLA -> withinSla = Math.addExact(withinSla, 1);
                case AT_RISK -> atRisk = Math.addExact(atRisk, 1);
                case BREACHED -> breached = Math.addExact(breached, 1);
            }
        }

        Group toGroup(Key key) {
            return new Group(key.team(), key.kind(), total, zeroToOne, twoToFive,
                    sixToTen, overTen, withinSla, atRisk, breached);
        }
    }
}