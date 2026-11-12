package com.saumay.payments.config;

import com.saumay.payments.application.CaseIntakeService;
import com.saumay.payments.infrastructure.messaging.RunCompletedListener;
import com.saumay.payments.infrastructure.persistence.JdbcRunEventProcessor;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

/** Wires the opt-in RUN_COMPLETED case-intake consumer. */
@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "payments.events", name = "enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class KafkaRunConsumerConfig {

    @Bean
    JdbcRunEventProcessor jdbcRunEventProcessor(
            DataSource dataSource, CaseIntakeService intake) {
        return new JdbcRunEventProcessor(dataSource, intake::openCases);
    }

    @Bean
    RunCompletedListener runCompletedListener(JdbcRunEventProcessor processor) {
        return new RunCompletedListener(processor);
    }

    @Bean(name = "runEventKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<String, String> runEventKafkaListenerContainerFactory(
            KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(
                kafkaProperties.buildConsumerProperties());

        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(properties));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());

        return factory;
    }
}