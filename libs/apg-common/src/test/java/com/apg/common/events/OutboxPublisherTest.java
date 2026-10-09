package com.apg.common.events;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

class OutboxPublisherTest {
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.1.2").asCompatibleSubstituteFor("apache/kafka"));
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;

    @BeforeAll
    static void start() {
        pg.start();
        kafka.start();
        DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("""
                CREATE TABLE outbox_events (id UUID PRIMARY KEY, event_type VARCHAR(60) NOT NULL, aggregate_id UUID NOT NULL,
                payload TEXT NOT NULL, occurred_at TIMESTAMPTZ NOT NULL, published_at TIMESTAMPTZ)""");
    }

    @org.junit.jupiter.api.BeforeEach
    void cleanOutbox() {
        jdbc.update("DELETE FROM outbox_events");     // tests share one table; publishBatch sends everything unpublished
    }

    @AfterAll
    static void stop() {
        kafka.stop();
        pg.stop();
    }

    private KafkaTemplate<String, String> template(String bootstrap) {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 1500,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000, ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1500)));
    }

    private UUID add(String type, UUID aggregate, String payload, int secondsAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO outbox_events (id, event_type, aggregate_id, payload, occurred_at) VALUES (?, ?, ?, ?, now() - make_interval(secs => ?))",
                id, type, aggregate, payload, secondsAgo);
        return id;
    }

    private List<ConsumerRecord<String, String>> consume(String topic, int expected) {
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(), ConsumerConfig.GROUP_ID_CONFIG, "t-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class, ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            c.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> out = new ArrayList<>();
            long end = System.currentTimeMillis() + 20_000;
            while (out.size() < expected && System.currentTimeMillis() < end) c.poll(Duration.ofMillis(500)).forEach(out::add);
            return out;
        }
    }

    @Test
    void publishesInOrderMarksPublishedAndNeverRepublishes() {
        OutboxPublisher p = new OutboxPublisher(jdbc, tx, template(kafka.getBootstrapServers()), Clock.systemUTC(), 100, 1000);
        UUID agg = UUID.randomUUID();
        UUID first = add("test.ordered", agg, "{\"n\":1}", 30);
        add("test.ordered", agg, "{\"n\":2}", 20);
        add("test.ordered", agg, "{\"n\":3}", 10);

        assertEquals(3, p.publishBatch());
        assertEquals(0, p.publishBatch(), "already published events are not sent again");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'test.ordered' AND published_at IS NULL", Integer.class));

        List<ConsumerRecord<String, String>> got = consume("test.ordered", 3);
        assertEquals(List.of("{\"n\":1}", "{\"n\":2}", "{\"n\":3}"), got.stream().map(ConsumerRecord::value).toList());
        assertTrue(got.stream().allMatch(r -> agg.toString().equals(r.key())), "key = aggregate id");
        assertEquals(first.toString(), new String(got.get(0).headers().lastHeader("eventId").value(), StandardCharsets.UTF_8));
        assertEquals("test.ordered", new String(got.get(0).headers().lastHeader("eventType").value(), StandardCharsets.UTF_8));
    }

    @Test
    void brokerOutageLeavesEventsUnpublishedAndTheyGoOutLater() {
        OutboxPublisher down = new OutboxPublisher(jdbc, tx, template("localhost:1"), Clock.systemUTC(), 100, 1000);
        UUID agg = UUID.randomUUID();
        add("test.outage", agg, "{\"n\":1}", 5);
        add("test.outage", agg, "{\"n\":2}", 4);
        assertEquals(0, down.publishBatch(), "nothing is published, nothing is lost");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'test.outage' AND published_at IS NULL", Integer.class));

        OutboxPublisher up = new OutboxPublisher(jdbc, tx, template(kafka.getBootstrapServers()), Clock.systemUTC(), 100, 1000);
        assertEquals(2, up.publishBatch());
        assertEquals(List.of("{\"n\":1}", "{\"n\":2}"), consume("test.outage", 2).stream().map(ConsumerRecord::value).toList());
    }

    @Test
    void batchSizeLimitsEachRound() {
        OutboxPublisher p = new OutboxPublisher(jdbc, tx, template(kafka.getBootstrapServers()), Clock.systemUTC(), 2, 1000);
        UUID agg = UUID.randomUUID();
        for (int i = 0; i < 5; i++) add("test.batch", agg, "{\"n\":" + i + "}", 50 - i);
        assertEquals(2, p.publishBatch());
        assertEquals(2, p.publishBatch());
        assertEquals(1, p.publishBatch());
        assertEquals(5, consume("test.batch", 5).size());
    }
}
