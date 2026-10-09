package com.apg.common.events;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Publishes the service outbox to Kafka when apg.events.publisher.enabled=true and spring-kafka is on the classpath. */
@AutoConfiguration(after = {JdbcTemplateAutoConfiguration.class, KafkaAutoConfiguration.class, TransactionAutoConfiguration.class})
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(name = "apg.events.publisher.enabled", havingValue = "true")
public class ApgEventsAutoConfiguration {
    @Bean
    @ConditionalOnBean({JdbcTemplate.class, KafkaTemplate.class, TransactionTemplate.class})
    @ConditionalOnMissingBean
    OutboxPublisher outboxPublisher(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
                                    @Value("${apg.events.publisher.batch-size:100}") int batchSize,
                                    @Value("${apg.events.publisher.interval-ms:1000}") long intervalMs) {
        return new OutboxPublisher(jdbc, tx, kafka, Clock.systemUTC(), batchSize, intervalMs);
    }
}
