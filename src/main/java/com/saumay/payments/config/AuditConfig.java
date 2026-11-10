package com.saumay.payments.config;

import com.saumay.payments.application.AuditLog;
import com.saumay.payments.application.AuditReader;
import com.saumay.payments.infrastructure.persistence.JdbcAuditReader;
import com.saumay.payments.infrastructure.persistence.JdbcAuditLog;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the audit chain. Every store that changes audited state receives this one log. */
@Configuration
public class AuditConfig {

    @Bean
    AuditReader auditReader(DataSource dataSource) {
        return new JdbcAuditReader(dataSource);
    }

    @Bean
    AuditLog auditLog(DataSource dataSource) {
        return new JdbcAuditLog(dataSource);
    }
}