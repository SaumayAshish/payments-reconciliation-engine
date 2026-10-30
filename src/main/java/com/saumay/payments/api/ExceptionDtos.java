package com.saumay.payments.api;

import com.saumay.payments.application.CaseReader;
import com.saumay.payments.application.CaseRecord;
import java.time.Instant;
import java.util.List;

/**
 * The wire shapes of the exception API. Domain and persistence types never cross the boundary: enums go out as their
 * names, dates and instants as ISO-8601 strings and money as integer minor units with its currency code.
 */
public final class ExceptionDtos {

    private ExceptionDtos() {
    }

    /** Every write names the version the caller last read; a stale version is refused with 409. */
    public record AssignRequest(Long expectedVersion, String owner, String reason) {
    }

    public record InvestigateRequest(Long expectedVersion) {
    }

    public record EscalateRequest(Long expectedVersion, String reason) {
    }

    public record CommentRequest(String body) {
    }

    /** One statement line against one ledger entry is a manual match; one against several (or the reverse) a split. */
    public record MatchRequest(Long expectedVersion, String reason, List<Long> statementSeqs, List<Long> ledgerSeqs) {
    }

    public record UnmatchRequest(String reason) {
    }

    public record CommentCreated(long caseId, int ordinal) {
    }

    public record CaseDto(long id, String kind, String caseClass, long amountMinor, String currency, String status,
            String priority, String team, String routingRule, String owner, String createdBusinessDate,
            int slaBusinessDays, String dueBusinessDate, long version) {

        static CaseDto from(CaseRecord r) {
            return new CaseDto(r.id(), r.kind().name(), r.caseClass().name(), r.amountMinor(), r.currency().code(),
                    r.status().name(), r.priority().name(), r.team().name(), r.routingRule(), r.owner(),
                    r.createdBusinessDate().toString(), r.slaBusinessDays(), r.dueBusinessDate().toString(),
                    r.version());
        }
    }

    public record CasePageDto(List<CaseDto> items, Long nextAfter) {

        static CasePageDto from(CaseReader.CasePage page) {
            return new CasePageDto(page.items().stream().map(CaseDto::from).toList(), page.nextAfterId());
        }
    }

    public record ActivityDto(int ordinal, String type, String actor, String from, String to, String detail,
            String at) {
    }

    public record CommentDto(int ordinal, String author, String body, String at) {
    }

    public record AssignmentDto(int ordinal, String owner, String team, String assignedBy, String reason, String at) {
    }

    public record ClaimDto(long seq, String action, String source, Long statementSeq, Long ledgerSeq, String actor) {
    }

    public record CaseDetailDto(CaseDto summary, String origin, Long originRunId, Integer originOrdinal,
            Long linkedCaseId, String resolutionType, String resolutionReason, String resolvedAt, String createdAt,
            String updatedAt, List<ActivityDto> activity, List<CommentDto> comments,
            List<AssignmentDto> assignments, List<ClaimDto> claims) {

        static CaseDetailDto from(CaseReader.CaseDetail d) {
            return new CaseDetailDto(CaseDto.from(d.record()), d.origin(), d.originRunId(), d.originOrdinal(),
                    d.linkedCaseId(), d.resolutionType(), d.resolutionReason(), text(d.resolvedAt()),
                    text(d.createdAt()), text(d.updatedAt()),
                    d.activity().stream().map(a -> new ActivityDto(a.ordinal(), a.type().name(), a.actor(),
                            name(a.from()), name(a.to()), a.detail(), text(a.at()))).toList(),
                    d.comments().stream().map(c -> new CommentDto(c.ordinal(), c.author(), c.body(),
                            text(c.at()))).toList(),
                    d.assignments().stream().map(a -> new AssignmentDto(a.ordinal(), a.owner(), a.team().name(),
                            a.assignedBy(), a.reason(), text(a.at()))).toList(),
                    d.claims().stream().map(c -> new ClaimDto(c.seq(), c.action(), c.source(), c.statementSeq(),
                            c.ledgerSeq(), c.actor())).toList());
        }

        private static String text(Instant instant) {
            return instant == null ? null : instant.toString();
        }

        private static String name(Enum<?> value) {
            return value == null ? null : value.name();
        }
    }
}