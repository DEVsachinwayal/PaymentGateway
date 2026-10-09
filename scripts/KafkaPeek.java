import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Read-only look at a Kafka topic (the native Kafka image used locally ships no CLI tools).
 * Usage via scripts/kafka-peek.sh: KafkaPeek <bootstrap> <topic> [max-records=20]
 * Uses a throw-away consumer group and never commits offsets, so it cannot disturb the real consumers.
 * Dead-letter records carry the failure reason in the kafka_dlt-exception-* headers (printed below).
 */
public class KafkaPeek {
    public static void main(String[] args) {
        String bootstrap = args[0], topic = args[1];
        int max = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "peek-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            c.subscribe(List.of(topic));
            int n = 0;
            long end = System.currentTimeMillis() + 15_000;
            while (n < max && System.currentTimeMillis() < end) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(500))) {
                    System.out.printf("[%d:%d] key=%s%n  value=%s%n", r.partition(), r.offset(), r.key(), r.value());
                    for (Header h : r.headers()) {
                        if (h.key().startsWith("kafka_dlt-exception-message") || h.key().startsWith("kafka_dlt-exception-fqcn")) {
                            System.out.printf("  %s=%s%n", h.key(), new String(h.value()));
                        }
                    }
                    if (++n >= max) break;
                }
            }
            System.out.println(n + " record(s) shown from " + topic);
        }
    }
}
