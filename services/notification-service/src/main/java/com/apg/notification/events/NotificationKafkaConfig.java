package com.apg.notification.events;

import com.apg.common.api.ApiException;
import com.apg.notification.service.NotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
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
 * Consumes merchant-facing events. Transient failures (merchant-service or database down) are retried with backoff; malformed
 * events go straight to {@code <topic>.DLT} so they cannot block the partition and are never lost.
 */
@Configuration
@ConditionalOnProperty(name = "apg.events.consumer.enabled", havingValue = "true")
public class NotificationKafkaConfig {
    public static final List<String> TOPICS = List.of("payment.success", "payment.failed", "payment.unknown", "refund.success", "refund.failed");

    @Bean
    List<NewTopic> notificationTopics() {
        return TOPICS.stream().flatMap(t -> java.util.stream.Stream.of(
                TopicBuilder.name(t).partitions(3).replicas(1).build(),
                TopicBuilder.name(t + ".DLT").partitions(1).replicas(1).build())).toList();
    }

    @Bean
    CommonErrorHandler notificationErrorHandler(KafkaOperations<Object, Object> template, io.micrometer.core.instrument.MeterRegistry meters) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(2 * 60_000L);
        // count every record parked on a dead-letter topic: a growing DLQ is an alert (docs/OBSERVABILITY.md)
        org.springframework.kafka.listener.ConsumerRecordRecoverer counting = (record, ex) -> {
            meters.counter("apg_dlq_records_total", "topic", record.topic(), "service", "notification").increment();
            recoverer.accept(record, ex);
        };
        DefaultErrorHandler handler = new DefaultErrorHandler(counting, backOff);
        handler.addNotRetryableExceptions(ApiException.class, JsonProcessingException.class, IllegalArgumentException.class);
        return handler;
    }

    @Component
    @ConditionalOnProperty(name = "apg.events.consumer.enabled", havingValue = "true")
    public static class Listener {
        private final NotificationService service;

        public Listener(NotificationService service) {
            this.service = service;
        }

        @KafkaListener(topics = {"payment.success", "payment.failed", "payment.unknown", "refund.success", "refund.failed"}, groupId = "notification-service")
        public void on(String value) throws JsonProcessingException {
            service.onEvent(value);
        }
    }
}
