package com.apg.notification;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Kafka -> job -> signed HTTP delivery, end to end, with the real worker running. */
@Testcontainers
@SpringBootTest(properties = {"POSTGRES_USER=ignored", "POSTGRES_PASSWORD=ignored", "INTERNAL_SERVICE_TOKEN=internal-token-for-tests-123",
        "apg.events.consumer.enabled=true", "apg.notification.allow-insecure-urls=true", "apg.notification.worker.interval=500ms"})
class KafkaConsumerTest {
    static final FakeWorld world = new FakeWorld();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.1.2").asCompatibleSubstituteFor("apache/kafka"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("apg.notification.merchant-service-uri", world::directoryUrl);
    }

    @AfterAll
    static void stop() { world.close(); }

    @Autowired KafkaTemplate<String, String> template;
    @Autowired JdbcTemplate jdbc;

    private void until(BooleanSupplier c, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 40_000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for " + what);
            Thread.sleep(200);
        }
    }

    @Test
    void anEventOnKafkaBecomesOneSignedWebhookAndPoisonIsParked() throws Exception {
        UUID merchant = UUID.randomUUID(), eventId = UUID.randomUUID();
        String path = "/kafka-" + UUID.randomUUID();
        world.subscribe(merchant, "payment.success", path, "whsec_kafka");
        String event = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"payment.success\",\"version\":1,\"occurredAt\":\"2026-10-09T10:00:00Z\","
                + "\"aggregateId\":\"" + UUID.randomUUID() + "\",\"merchantId\":\"" + merchant + "\",\"payload\":{\"amount\":500,\"currency\":\"INR\"}}";

        template.send("payment.success", eventId.toString(), event).get();
        template.send("payment.success", eventId.toString(), event).get();        // duplicate delivery from the broker
        template.send("payment.success", "bad", "this is not json").get();         // poison
        until(() -> world.hitsFor(path) >= 1, "the webhook to arrive");
        until(() -> "DELIVERED".equals(jdbc.queryForList("select status from notification_jobs where event_id = ?", String.class, eventId).stream().findFirst().orElse("")),
                "the job to be marked delivered");
        Thread.sleep(1500);
        assertEquals(1, world.hitsFor(path), "duplicate Kafka delivery must not notify twice");

        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(), ConsumerConfig.GROUP_ID_CONFIG, "t-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class, ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            c.subscribe(List.of("payment.success.DLT"));
            List<ConsumerRecord<String, String>> got = new ArrayList<>();
            long end = System.currentTimeMillis() + 30_000;
            while (got.isEmpty() && System.currentTimeMillis() < end) c.poll(Duration.ofMillis(500)).forEach(got::add);
            assertEquals("this is not json", got.get(0).value());
        }
    }
}
