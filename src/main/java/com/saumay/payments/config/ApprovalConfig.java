package com.saumay.payments.config;

import com.saumay.payments.application.ApprovalConfigurationStore;
import com.saumay.payments.application.ApprovalPendingGuard;
import com.saumay.payments.application.ApprovalStore;
import com.saumay.payments.application.PendingApprovalGuard;
import com.saumay.payments.application.AuditLog;
import com.saumay.payments.application.CaseMatchStore;
import com.saumay.payments.application.ControlledResolutionService;
import com.saumay.payments.domain.approval.ApprovalThreshold;
import com.saumay.payments.infrastructure.persistence.JdbcApprovalConfigurationStore;
import com.saumay.payments.infrastructure.persistence.JdbcApprovalStore;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires maker-checker approvals (FRD-08). The threshold is a built-in default until versioned configuration lands. */
@Configuration
public class ApprovalConfig {

    @Bean
    ApprovalStore approvalStore(DataSource dataSource, AuditLog audit) {
        return new JdbcApprovalStore(dataSource, audit);
    }

    @Bean
    ApprovalConfigurationStore approvalConfigurationStore(DataSource dataSource, AuditLog audit) {
        return new JdbcApprovalConfigurationStore(dataSource, audit);
    }

    @Bean
    PendingApprovalGuard pendingApprovalGuard(ApprovalStore approvals) {
        return new ApprovalPendingGuard(approvals);
    }

    @Bean
    ApprovalThreshold approvalThreshold() {
        return ApprovalThreshold.defaults();
    }

    @Bean
    ControlledResolutionService controlledResolutionService(ApprovalStore approvals, CaseMatchStore cases,
            ApprovalConfigurationStore configuration, Clock clock) {
        return new ControlledResolutionService(approvals, cases, configuration, clock);
    }
}