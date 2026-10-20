package com.saumay.payments.config;

import com.saumay.payments.infrastructure.persistence.JdbcIngestionReader;
import com.saumay.payments.infrastructure.persistence.JdbcIngestionStore;
import com.saumay.payments.ingestion.IngestionReader;
import com.saumay.payments.ingestion.IngestionStore;
import com.saumay.payments.ingestion.service.IngestionService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the plain-Java core to its adapters. The only place that knows both sides. */
@Configuration
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IngestionStore ingestionStore(DataSource dataSource) {
        return new JdbcIngestionStore(dataSource);
    }

    @Bean
    IngestionReader ingestionReader(DataSource dataSource) {
        return new JdbcIngestionReader(dataSource);
    }

    @Bean
    IngestionService ingestionService(IngestionStore store,
                                      @Value("${payments.ingestion.max-bytes:10485760}") long maxBytes) {
        return new IngestionService(store, maxBytes);
    }
}