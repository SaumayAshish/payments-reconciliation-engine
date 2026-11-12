package com.saumay.payments.config;

import com.saumay.payments.application.OutboxDeliveryStore;
import com.saumay.payments.infrastructure.persistence.JdbcOutboxDeliveryStore;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires outbox delivery state storage. */
@Configuration
public class OutboxConfig {

    @Bean
    OutboxDeliveryStore outboxDeliveryStore(DataSource dataSource) {
        return new JdbcOutboxDeliveryStore(dataSource);
    }
}