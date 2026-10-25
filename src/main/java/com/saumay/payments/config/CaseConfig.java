package com.saumay.payments.config;

import com.saumay.payments.application.CaseIntakeService;
import com.saumay.payments.application.CaseIntakeStore;
import com.saumay.payments.domain.cases.RoutingConfig;
import com.saumay.payments.infrastructure.persistence.JdbcCaseIntakeStore;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires exception cases: the JDBC adapter behind its port and the plain-Java services. The routing configuration
 * is the standard one for now; externalised, versioned and audited configuration arrives in Phase 5.
 */
@Configuration
public class CaseConfig {

    @Bean
    RoutingConfig routingConfig() {
        return RoutingConfig.defaults();
    }

    @Bean
    CaseIntakeStore caseIntakeStore(DataSource dataSource, Clock clock) {
        return new JdbcCaseIntakeStore(dataSource, clock);
    }

    @Bean
    CaseIntakeService caseIntakeService(CaseIntakeStore store, RoutingConfig routingConfig) {
        return new CaseIntakeService(store, routingConfig);
    }
}