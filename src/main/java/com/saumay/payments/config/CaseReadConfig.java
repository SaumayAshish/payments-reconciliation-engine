package com.saumay.payments.config;

import com.saumay.payments.application.CaseReader;
import com.saumay.payments.infrastructure.persistence.JdbcCaseReader;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the case queue and case view reader. */
@Configuration
public class CaseReadConfig {

    @Bean
    CaseReader caseReader(DataSource dataSource) {
        return new JdbcCaseReader(dataSource);
    }
}