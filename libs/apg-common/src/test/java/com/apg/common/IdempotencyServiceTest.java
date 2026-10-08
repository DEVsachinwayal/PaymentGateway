package com.apg.common;

import static org.junit.jupiter.api.Assertions.*;

import com.apg.common.api.ApiException;
import com.apg.common.idempotency.IdempotencyService;
import com.apg.common.idempotency.IdempotencyService.Outcome;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = IdempotencyServiceTest.Cfg.class)
class IdempotencyServiceTest {
    @Configuration
    @EnableAutoConfiguration
    static class Cfg {}

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired IdempotencyService idem;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void noop() {}

    private void ensureTable() {
        jdbc.execute("DROP TABLE IF EXISTS idempotency_keys");
        jdbc.execute(IdempotencyService.TABLE_DDL);
    }

    @Test void firstCallRunsReplayDoesNotAndDifferentBodyIsRejected() {
        ensureTable();
        UUID m = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        Map<String, Object> req = Map.of("amount", 100);

        Outcome a = idem.execute(m, "k1", req, 201, () -> Map.of("n", runs.incrementAndGet()));
        Outcome b = idem.execute(m, "k1", req, 201, () -> Map.of("n", runs.incrementAndGet()));
        assertFalse(a.replay());
        assertTrue(b.replay());
        assertEquals(a.body(), b.body());
        assertEquals(1, runs.get());

        ApiException ex = assertThrows(ApiException.class,
                () -> idem.execute(m, "k1", Map.of("amount", 999), 201, () -> Map.of("n", 99)));
        assertEquals("IDEMPOTENCY_KEY_REUSED", ex.code());
        assertEquals(422, ex.status().value());

        // same key for another merchant is independent
        Outcome other = idem.execute(UUID.randomUUID(), "k1", Map.of("amount", 999), 201, () -> Map.of("n", 7));
        assertFalse(other.replay());
    }

    @Test void missingKeyRejected() {
        ensureTable();
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", assertThrows(ApiException.class,
                () -> idem.execute(UUID.randomUUID(), " ", Map.of(), 201, () -> "x")).code());
    }

    @Test void failedActionRollsBackMarkerSoRetryWorks() {
        ensureTable();
        UUID m = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> idem.execute(m, "k2", Map.of("a", 1), 201, () -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals(0, jdbc.queryForObject("select count(*) from idempotency_keys", Integer.class));
        Outcome ok = idem.execute(m, "k2", Map.of("a", 1), 201, () -> Map.of("ok", true));
        assertFalse(ok.replay());
    }

    @Test void concurrentDuplicatesExecuteActionExactlyOnce() throws Exception {
        ensureTable();
        UUID m = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                return idem.execute(m, "same", Map.of("x", 1), 201, () -> {
                    try { Thread.sleep(150); } catch (InterruptedException ignored) { }
                    return Map.of("run", runs.incrementAndGet());
                });
            }));
        }
        start.countDown();
        String body = null;
        int replays = 0;
        for (Future<Outcome> f : fs) {
            Outcome o = f.get(30, TimeUnit.SECONDS);
            if (body == null) body = o.body();
            assertEquals(body, o.body());
            if (o.replay()) replays++;
        }
        pool.shutdown();
        assertEquals(1, runs.get());
        assertEquals(7, replays);
    }
}
