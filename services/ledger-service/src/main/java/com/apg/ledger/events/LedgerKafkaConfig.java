package com.apg.ledger.events;

import com.apg.common.api.ApiException;
import com.apg.ledger.service.LedgerEvents;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka side of the ledger (spec 17): consumes the money events and posts them through the same idempotent handler the HTTP
 * endpoint uses. Redelivery is harmless (idempotent posting). Failures: transient ones (database down...) are retried with
 * exponential backoff, then - like malformed or invalid events, which are never retried - the record is parked on
 * {@code <topic>.DLT} so one poison event cannot block the partition and nothing is silently dropped.
 */
@Configuration
@ConditionalOnProperty(name = "apg.events.consumer.enabled", havingValue = "true")
public class LedgerKafkaConfig {
    public static final List<String> TOPICS = List.of("payment.success", "refund.success", "settlement.created");

    @Bean
    List<NewTopic> ledgerTopics() {
        return TOPICS.stream().flatMap(t -> java.util.stream.Stream.of(
                TopicBuilder.name(t).partitions(3).replicas(1).build(),
                TopicBuilder.name(t + ".DLT").partitions(1).replicas(1).build())).toList();
    }

    @Bean
    CommonErrorHandler ledgerErrorHandler(KafkaOperations<Object, Object> template, io.micrometer.core.instrument.MeterRegistry meters) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));          // any partition: the DLT is small
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(2 * 60_000L);
        // count every record parked on a dead-letter topic: a growing DLQ is an alert (docs/OBSERVABILITY.md)
        org.springframework.kafka.listener.ConsumerRecordRecoverer counting = (record, ex) -> {
            meters.counter("apg_dlq_records_total", "topic", record.topic(), "service", "ledger").increment();
            recoverer.accept(record, ex);
        };
        DefaultErrorHandler handler = new DefaultErrorHandler(counting, backOff);
        handler.addNotRetryableExceptions(ApiException.class, JsonProcessingException.class, IllegalArgumentException.class);
        return handler;
    }

    @Component
    @ConditionalOnProperty(name = "apg.events.consumer.enabled", havingValue = "true")
    public static class Listener {
        private static final Logger log = LoggerFactory.getLogger(Listener.class);
        private final LedgerEvents events;
        private final ObjectMapper mapper;

        public Listener(LedgerEvents events, ObjectMapper mapper) {
            this.events = events;
            this.mapper = mapper;
        }

        @KafkaListener(topics = {"payment.success", "refund.success", "settlement.created"}, groupId = "ledger-service")
        public void on(String value) throws JsonProcessingException {
            var result = events.handle(mapper.readTree(value));
            log.debug("ledger event handled: {}", result.outcome());
        }
    }
}
