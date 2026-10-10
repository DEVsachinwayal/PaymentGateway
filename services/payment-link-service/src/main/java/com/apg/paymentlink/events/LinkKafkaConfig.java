package com.apg.paymentlink.events;

import com.apg.common.api.ApiException;
import com.apg.paymentlink.service.LinkService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
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

/** Marks links as paid when payment-service announces payment.success (idempotent; the lazy status check in LinkService covers lost events). */
@Configuration
@ConditionalOnProperty(name = "apg.events.consumer.enabled", havingValue = "true")
public class LinkKafkaConfig {
    @Bean
    List<NewTopic> paymentLinkTopics() {
        return List.of(TopicBuilder.name("payment.success").partitions(3).replicas(1).build(), TopicBuilder.name("payment.success.DLT").partitions(1).replicas(1).build());
    }

    @Bean
    CommonErrorHandler paymentLinkErrorHandler(KafkaOperations<Object, Object> template, io.micrometer.core.instrument.MeterRegistry meters) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template, (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(2 * 60_000L);
        org.springframework.kafka.listener.ConsumerRecordRecoverer counting = (record, ex) -> {
            meters.counter("apg_dlq_records_total", "topic", record.topic(), "service", "payment-link").increment();
            recoverer.accept(record, ex);
        };
        DefaultErrorHandler handler = new DefaultErrorHandler(counting, backOff);
        handler.addNotRetryableExceptions(ApiException.class, JsonProcessingException.class, IllegalArgumentException.class);
        return handler;
    }

    @Component
    @ConditionalOnProperty(name = "apg.events.consumer.enabled", havingValue = "true")
    public static class Listener {
        private final LinkService links;
        private final ObjectMapper mapper;

        public Listener(LinkService links, ObjectMapper mapper) {
            this.links = links;
            this.mapper = mapper;
        }

        @KafkaListener(topics = "payment.success", groupId = "payment-link-service")
        public void on(String value) throws JsonProcessingException {
            JsonNode e = mapper.readTree(value);
            links.paymentSucceeded(UUID.fromString(e.path("aggregateId").asText()));
        }
    }
}
