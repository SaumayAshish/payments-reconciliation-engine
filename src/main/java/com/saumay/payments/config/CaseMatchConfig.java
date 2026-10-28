package com.saumay.payments.config;

import com.saumay.payments.application.CaseMatchService;
import com.saumay.payments.application.CaseMatchStore;
import com.saumay.payments.domain.cases.RoutingConfig;
import com.saumay.payments.infrastructure.persistence.JdbcCaseMatchStore;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires manual match, split and unmatch (FRD-12). */
@Configuration
public class CaseMatchConfig {

    @Bean
    CaseMatchStore caseMatchStore(DataSource dataSource) {
        return new JdbcCaseMatchStore(dataSource);
    }

    @Bean
    CaseMatchService caseMatchService(CaseMatchStore store, RoutingConfig routing, Clock clock) {
        return new CaseMatchService(store, routing, clock);
    }
}