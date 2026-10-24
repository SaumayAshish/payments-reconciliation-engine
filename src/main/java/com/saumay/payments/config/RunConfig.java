package com.saumay.payments.config;

import com.saumay.payments.application.ReconciliationRunService;
import com.saumay.payments.application.RunResultReader;
import com.saumay.payments.application.RunStore;
import com.saumay.payments.infrastructure.persistence.JdbcRunResultReader;
import com.saumay.payments.infrastructure.persistence.JdbcRunStore;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires reconciliation runs: the JDBC adapters behind their ports, and the plain-Java run service. */
@Configuration
public class RunConfig {

    @Bean
    RunStore runStore(DataSource dataSource) {
        return new JdbcRunStore(dataSource);
    }

    @Bean
    RunResultReader runResultReader(DataSource dataSource) {
        return new JdbcRunResultReader(dataSource);
    }

    @Bean
    ReconciliationRunService reconciliationRunService(RunStore store, Clock clock) {
        return new ReconciliationRunService(store, clock);
    }
}