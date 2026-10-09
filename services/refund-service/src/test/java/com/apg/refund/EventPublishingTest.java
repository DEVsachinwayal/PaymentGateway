package com.apg.refund;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Proves the apg-common outbox relay is wired into a real service: a created refund shows up on Kafka as refund.created. */
@Testcontainers
@SpringBootTest(properties = {"POSTGRES_USER=ignored", "POSTGRES_PASSWORD=ignored", "INTERNAL_SERVICE_TOKEN=internal-token-for-tests-123",
        "apg.refund.worker.enabled=false", "apg.events.publisher.enabled=true", "apg.events.publisher.interval-ms=200"})
@AutoConfigureMockMvc
class EventPublishingTest {
    static final String TOKEN = "internal-token-for-tests-123";
    static final FakePeers peers = new FakePeers();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.1.2").asCompatibleSubstituteFor("apache/kafka"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("apg.refund.provider-service-uri", peers::baseUrl);
        r.add("apg.refund.payment-service-uri", peers::baseUrl);
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @AfterAll
    static void stop() { peers.close(); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void aCreatedRefundIsPublishedToKafka() throws Exception {
        UUID merchant = UUID.randomUUID();
        FakePeers.Payment p = peers.addPayment(merchant, 5000, "SUCCESS");
        String res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/refunds").header("X-Internal-Token", TOKEN).header("X-Merchant-Id", merchant.toString())
                .header("X-Auth-Type", "API_KEY").header("Idempotency-Key", "k1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentId\":\"" + p.id() + "\",\"amount\":1200}")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String refundId = mapper.readTree(res).path("data").path("id").asText();

        JsonNode env = null;
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(), ConsumerConfig.GROUP_ID_CONFIG, "t-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class, ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            c.subscribe(List.of("refund.created"));
            long end = System.currentTimeMillis() + 30_000;
            List<ConsumerRecord<String, String>> got = new ArrayList<>();
            while (got.isEmpty() && System.currentTimeMillis() < end) c.poll(Duration.ofMillis(500)).forEach(got::add);
            assertFalse(got.isEmpty(), "refund.created must reach Kafka");
            assertEquals(refundId, got.get(0).key());
            env = mapper.readTree(got.get(0).value());
        }
        assertEquals("refund.created", env.path("eventType").asText());
        assertEquals(merchant.toString(), env.path("merchantId").asText());
        assertEquals(1200, env.path("payload").path("amount").asLong());
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?::uuid and event_type = 'refund.created' and published_at is null",
                Integer.class, refundId));
    }
}
