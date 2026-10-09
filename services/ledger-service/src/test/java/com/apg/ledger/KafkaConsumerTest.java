package com.apg.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = {"POSTGRES_USER=ignored", "POSTGRES_PASSWORD=ignored", "INTERNAL_SERVICE_TOKEN=internal-token-for-tests-123",
        "apg.events.consumer.enabled=true"})
class KafkaConsumerTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.1.2").asCompatibleSubstituteFor("apache/kafka"));

    @Autowired KafkaTemplate<String, String> template;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;

    private String event(String type, UUID eventId, UUID aggregate, UUID merchant, Object amount) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type + "\",\"version\":1,\"occurredAt\":\"2026-10-09T10:00:00Z\",\"aggregateId\":\""
                + aggregate + "\",\"merchantId\":\"" + merchant + "\",\"payload\":{\"amount\":" + amount + ",\"currency\":\"INR\",\"provider\":\"payu\"}}";
    }

    private void until(BooleanSupplier c, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 40_000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for " + what);
            Thread.sleep(200);
        }
    }

    private int txCount(UUID ref) { return jdbc.queryForObject("select count(*) from ledger_transactions where reference_id = ?", Integer.class, ref); }

    private List<ConsumerRecord<String, String>> dlt(String topic, int expected) {
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(), ConsumerConfig.GROUP_ID_CONFIG, "t-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class, ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            c.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> out = new ArrayList<>();
            long end = System.currentTimeMillis() + 30_000;
            while (out.size() < expected && System.currentTimeMillis() < end) c.poll(Duration.ofMillis(500)).forEach(out::add);
            return out;
        }
    }

    @Test
    void kafkaEventsArePostedOnceInvalidOnesGoToTheDeadLetterTopicAndNothingBlocks() throws Exception {
        UUID merchant = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        String good = event("payment.success", UUID.randomUUID(), payment, merchant, 10000);

        template.send("payment.success", payment.toString(), good).get();
        until(() -> txCount(payment) == 1, "the capture to be posted");

        template.send("payment.success", payment.toString(), good).get();            // redelivery
        // a poison event (zero amount) and a garbage record are parked, not retried forever
        UUID badAggregate = UUID.randomUUID();
        template.send("payment.success", badAggregate.toString(), event("payment.success", UUID.randomUUID(), badAggregate, merchant, 0)).get();
        template.send("payment.success", "garbage", "this is not json").get();
        // and the partition keeps flowing afterwards
        UUID refund = UUID.randomUUID();
        template.send("refund.success", refund.toString(), event("refund.success", UUID.randomUUID(), refund, merchant, 2500)).get();
        until(() -> txCount(refund) == 1, "the refund after the poison events");

        assertEquals(1, txCount(payment), "redelivery must not post twice");
        assertEquals(0, txCount(badAggregate));
        assertEquals(2.0, meters.counter("apg_dlq_records_total", "topic", "payment.success", "service", "ledger").count(), "dead-lettered records are counted");
        assertTrue(meters.counter("apg_ledger_postings_total", "type", "CAPTURE").count() >= 1);
        List<ConsumerRecord<String, String>> parked = dlt("payment.success.DLT", 2);
        assertEquals(2, parked.size(), "both poison records are on the dead-letter topic");
        assertTrue(parked.stream().anyMatch(r -> r.value().equals("this is not json")));
        assertTrue(parked.get(0).headers().lastHeader("kafka_dlt-exception-fqcn") != null, "the failure reason travels with the record");
    }
}
