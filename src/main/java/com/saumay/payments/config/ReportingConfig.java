package com.saumay.payments.config;

import com.saumay.payments.application.AgeingReportReader;
import com.saumay.payments.application.AgeingReportService;
import com.saumay.payments.application.OperationalMetricsReader;
import com.saumay.payments.infrastructure.persistence.JdbcOperationalMetricsReader;
import com.saumay.payments.application.WorkloadReportReader;
import com.saumay.payments.infrastructure.persistence.JdbcWorkloadReportReader;
import com.saumay.payments.application.MatchReportReader;
import com.saumay.payments.infrastructure.persistence.JdbcMatchReportReader;
import com.saumay.payments.application.ResolutionReportReader;
import com.saumay.payments.application.ResolutionReportService;
import com.saumay.payments.infrastructure.persistence.JdbcResolutionReportReader;
import com.saumay.payments.application.CaseService;
import com.saumay.payments.application.SlaCandidateReader;
import com.saumay.payments.application.SlaEscalationService;
import com.saumay.payments.infrastructure.persistence.JdbcSlaCandidateReader;
import com.saumay.payments.infrastructure.persistence.JdbcAgeingReportReader;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the ageing report reader and plain-Java service. */
@Configuration
public class ReportingConfig {

    @Bean
    MatchReportReader matchReportReader(DataSource dataSource) {
        return new JdbcMatchReportReader(dataSource);
    }

    @Bean
    WorkloadReportReader workloadReportReader(DataSource dataSource) {
        return new JdbcWorkloadReportReader(dataSource);
    }

    @Bean
    OperationalMetricsReader operationalMetricsReader(DataSource dataSource) {
        return new JdbcOperationalMetricsReader(dataSource);
    }

    @Bean
    AgeingReportReader ageingReportReader(DataSource dataSource) {
        return new JdbcAgeingReportReader(dataSource);
    }

    @Bean
    SlaCandidateReader slaCandidateReader(DataSource dataSource) {
        return new JdbcSlaCandidateReader(dataSource);
    }

    @Bean
    SlaEscalationService slaEscalationService(SlaCandidateReader reader, CaseService cases) {
        return new SlaEscalationService(reader, cases);
    }

    @Bean
    ResolutionReportReader resolutionReportReader(DataSource dataSource) {
        return new JdbcResolutionReportReader(dataSource);
    }

    @Bean
    ResolutionReportService resolutionReportService(ResolutionReportReader reader) {
        return new ResolutionReportService(reader);
    }

    @Bean
    AgeingReportService ageingReportService(AgeingReportReader reader) {
        return new AgeingReportService(reader);
    }
}