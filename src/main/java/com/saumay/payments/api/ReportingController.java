package com.saumay.payments.api;

import com.saumay.payments.application.AgeingReportService;
import com.saumay.payments.application.WorkloadReportReader;
import com.saumay.payments.application.MatchReportReader;
import com.saumay.payments.application.ResolutionReportService;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Current-snapshot operational reports. */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportingController {

    private final AgeingReportService reports;
    private final ResolutionReportService resolutions;
    private final MatchReportReader matches;
    private final WorkloadReportReader workloads;

    public ReportingController(AgeingReportService reports, ResolutionReportService resolutions,
            MatchReportReader matches, WorkloadReportReader workloads) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.resolutions = Objects.requireNonNull(resolutions, "resolutions");
        this.matches = Objects.requireNonNull(matches, "matches");
        this.workloads = Objects.requireNonNull(workloads, "workloads");
    }

    public record WorkloadReport(java.util.List<WorkloadReportReader.Group> groups) {
        public WorkloadReport {
            groups = java.util.List.copyOf(groups);
        }
    }

    @GetMapping("/workload")
    public WorkloadReport workload() {
        return new WorkloadReport(workloads.currentWorkload());
    }

    @GetMapping("/match/{runId}")
    public MatchReportReader.Report match(@PathVariable("runId") long runId) {
        if (runId < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "runId must be positive");
        }
        return matches.completedRun(runId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND",
                        "No completed reconciliation run exists with that id"));
    }

    @GetMapping("/resolution-time")
    public ResolutionReportService.Report resolutionTime() {
        return resolutions.report();
    }

    @GetMapping("/ageing")
    public AgeingReportService.Report ageing(@RequestParam(name = "asOf") String asOf) {
        try {
            return reports.report(LocalDate.parse(asOf));
        } catch (DateTimeParseException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_INVALID",
                    "asOf must be an ISO date in YYYY-MM-DD form");
        }
    }
}