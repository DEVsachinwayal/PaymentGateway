package com.apg.refund;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.apg.refund.service.RefundService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {"POSTGRES_USER=ignored", "POSTGRES_PASSWORD=ignored",
        "INTERNAL_SERVICE_TOKEN=internal-token-for-tests-123", "apg.refund.worker.enabled=false"})
@AutoConfigureMockMvc
class RefundFlowTest {
    static final String TOKEN = "internal-token-for-tests-123";
    static final FakePeers peers = new FakePeers();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("apg.refund.provider-service-uri", peers::baseUrl);
        r.add("apg.refund.payment-service-uri", peers::baseUrl);
    }

    @AfterAll
    static void stop() { peers.close(); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RefundService service;
    @Autowired ObjectMapper mapper;

    private final UUID merchant = UUID.randomUUID();

    private MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder b, UUID m) {
        return b.header("X-Internal-Token", TOKEN).header("X-Merchant-Id", m.toString()).header("X-Auth-Type", "API_KEY")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions refund(UUID m, String key, String json) throws Exception {
        MockHttpServletRequestBuilder b = api(MockMvcRequestBuilders.post("/api/v1/refunds"), m).content(json);
        if (key != null) b.header("Idempotency-Key", key);
        return mvc.perform(b);
    }

    private String body(UUID payment, Long amount) {
        return "{\"paymentId\":\"" + payment + "\"" + (amount == null ? "" : ",\"amount\":" + amount) + ",\"reason\":\"customer request\"}";
    }

    private UUID create(FakePeers.Payment p, Long amount) throws Exception {
        String res = refund(merchant, UUID.randomUUID().toString(), body(p.id(), amount)).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(res).path("data").path("id").asText());
    }

    private String dbStatus(UUID refund) { return jdbc.queryForObject("select status from refunds where id = ?", String.class, refund); }

    private void makeDue(UUID refund) {
        jdbc.update("update refunds set next_check_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(5)), refund);
    }

    private String requestId(UUID refund) { return jdbc.queryForObject("select provider_request_id from refunds where id = ?", String.class, refund); }

    // ------------------------------------------------------------------ happy path

    @Test void fullRefundGoesThroughProviderAndSettlesThePayment() throws Exception {
        FakePeers.Payment p = peers.addPayment(merchant, 10000, "SUCCESS");
        String res = refund(merchant, "k-" + UUID.randomUUID(), body(p.id(), null)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("CREATED")).andExpect(jsonPath("$.data.amount").value(10000))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(mapper.readTree(res).path("data").path("id").asText());

        assertEquals("PROCESSING", dbStatus(id), "provider accepted -> processing, not success yet");
        assertEquals(1, jdbc.queryForObject("select count(*) from refunds where id = ? and refund_reference ~ '^RF[0-9a-f]{18}$'", Integer.class, id));
        assertEquals("REFUND_PENDING", peers.statusOf(p.id()));

        peers.statusAnswer.put(requestId(id), "SUCCESS");
        makeDue(id);
        assertEquals(1, service.checkDue());
        assertEquals("SUCCESS", dbStatus(id));
        assertEquals("REFUNDED", peers.statusOf(p.id()));
        assertTrue(jdbc.queryForObject("select payment_synced from refunds where id = ?", Boolean.class, id));

        List<String> events = jdbc.queryForList("select event_type from outbox_events where aggregate_id = ? order by occurred_at", String.class, id);
        assertEquals(List.of("refund.created", "refund.success"), events);
        JsonNode env = mapper.readTree(jdbc.queryForObject(
                "select payload from outbox_events where aggregate_id = ? and event_type = 'refund.success'", String.class, id));
        assertEquals("refund.success", env.path("eventType").asText());
        assertEquals(merchant.toString(), env.path("merchantId").asText());
        assertEquals(id.toString(), env.path("aggregateId").asText());
        assertEquals(p.id().toString(), env.path("payload").path("paymentId").asText());
        assertEquals("INR", env.path("payload").path("currency").asText());
        assertEquals(10000, env.path("payload").path("amount").asLong());
        assertFalse(env.path("eventId").asText().isBlank());
        mvc.perform(api(MockMvcRequestBuilders.get("/api/v1/refunds/" + id), merchant)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test void partialRefundsAddUpAndOverRefundIsBlocked() throws Exception {
        FakePeers.Payment p = peers.addPayment(merchant, 10000, "SUCCESS");
        UUID first = create(p, 6000L);
        // 6000 is still in flight: it already counts against the limit
        refund(merchant, UUID.randomUUID().toString(), body(p.id(), 5000L)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REFUND_EXCEEDS_PAYMENT"));
        peers.statusAnswer.put(requestId(first), "SUCCESS");
        makeDue(first);
        service.checkDue();
        assertEquals("PARTIALLY_REFUNDED", peers.statusOf(p.id()));

        UUID second = create(p, 4000L);
        peers.statusAnswer.put(requestId(second), "SUCCESS");
        makeDue(second);
        service.checkDue();
        assertEquals("REFUNDED", peers.statusOf(p.id()));

        refund(merchant, UUID.randomUUID().toString(), body(p.id(), 1L)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_REFUNDABLE"));
    }

    // ------------------------------------------------------------------ idempotency & validation

    @Test void sameKeyReplaysAndNeverRefundsTwice() throws Exception {
        FakePeers.Payment p = peers.addPayment(merchant, 5000, "SUCCESS");
        String key = "dup-" + UUID.randomUUID();
        int calls = peers.refundCalls.get();
        String first = refund(merchant, key, body(p.id(), 2000L)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String again = refund(merchant, key, body(p.id(), 2000L)).andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true")).andReturn().getResponse().getContentAsString();
        assertEquals(mapper.readTree(first).path("data").path("id"), mapper.readTree(again).path("data").path("id"));
        assertEquals(1, jdbc.queryForObject("select count(*) from refunds where payment_id = ?", Integer.class, p.id()));
        assertEquals(calls + 1, peers.refundCalls.get(), "provider must be called once");
        refund(merchant, key, body(p.id(), 2500L)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test void inputIsValidatedStrictly() throws Exception {
        FakePeers.Payment p = peers.addPayment(merchant, 5000, "SUCCESS");
        refund(merchant, null, body(p.id(), 100L)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        refund(merchant, "v1", "{\"paymentId\":\"" + p.id() + "\",\"amount\":10.5}").andExpect(status().isBadRequest());
        refund(merchant, "v2", "{\"paymentId\":\"" + p.id() + "\",\"amount\":-5}").andExpect(status().isBadRequest());
        refund(merchant, "v3", "{\"paymentId\":\"" + p.id() + "\",\"amount\":0}").andExpect(status().isBadRequest());
        refund(merchant, "v4", "{\"paymentId\":\"" + p.id() + "\",\"status\":\"SUCCESS\"}").andExpect(status().isBadRequest());
        refund(merchant, "v5", "{\"amount\":100}").andExpect(status().isBadRequest());
        refund(merchant, "v6", body(p.id(), 5001L)).andExpect(status().isUnprocessableEntity());
        assertEquals(0, jdbc.queryForObject("select count(*) from refunds where payment_id = ?", Integer.class, p.id()));
    }

    @Test void onlyOwnSuccessfulPaymentsCanBeRefunded() throws Exception {
        FakePeers.Payment other = peers.addPayment(UUID.randomUUID(), 5000, "SUCCESS");
        refund(merchant, "o1", body(other.id(), 100L)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_FOUND"));
        FakePeers.Payment unpaid = peers.addPayment(merchant, 5000, "CREATED");
        refund(merchant, "o2", body(unpaid.id(), 100L)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_REFUNDABLE"));
        refund(merchant, "o3", body(UUID.randomUUID(), 100L)).andExpect(status().isNotFound());

        // another merchant cannot read my refund
        FakePeers.Payment mine = peers.addPayment(merchant, 5000, "SUCCESS");
        UUID id = create(mine, 100L);
        mvc.perform(api(MockMvcRequestBuilders.get("/api/v1/refunds/" + id), UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test void authorizationRules() throws Exception {
        FakePeers.Payment p = peers.addPayment(merchant, 5000, "SUCCESS");
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/refunds")).andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/refunds").header("X-Internal-Token", TOKEN)).andExpect(status().isForbidden());
        MockHttpServletRequestBuilder dev = MockMvcRequestBuilders.post("/api/v1/refunds").header("X-Internal-Token", TOKEN)
                .header("X-Auth-Type", "USER").header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Roles", "MERCHANT_DEVELOPER")
                .header("X-Merchant-Id", merchant.toString()).header("Idempotency-Key", "dev1").contentType(MediaType.APPLICATION_JSON).content(body(p.id(), 100L));
        mvc.perform(dev).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/refunds").header("X-Internal-Token", TOKEN)
                .header("X-Auth-Type", "USER").header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Roles", "MERCHANT_DEVELOPER")
                .header("X-Merchant-Id", merchant.toString())).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ provider outcomes

    @Test void rejectedRefundFailsAndReleasesTheAmount() throws Exception {
        FakePeers.Payment p = peers.addPayment(merchant, 8000, "SUCCESS");
        peers.defaultRefundAnswer = "REJECTED";
        try {
            UUID id = create(p, null);
            assertEquals("FAILED", dbStatus(id));
            assertTrue(jdbc.queryForObject("select failure_message from refunds where id = ?", String.class, id).contains("Token already used"));
            assertEquals("SUCCESS", peers.statusOf(p.id()), "payment goes back to SUCCESS when nothing was refunded");
        } finally {
            peers.defaultRefundAnswer = "ACCEPTED";
        }
        create(p, null);   // the amount is available again
    }

    @Test void unknownProviderAnswerIsNeverFailedNeverRetriedAndStaysReserved() throws Exception {
        FakePeers.Payment p = peers.addPayment(merchant, 8000, "SUCCESS");
        peers.defaultRefundAnswer = "UNKNOWN";
        UUID id;
        int calls;
        try {
            id = create(p, null);
            calls = peers.refundCalls.get();
            assertEquals("VERIFY_REQUIRED", dbStatus(id));
        } finally {
            peers.defaultRefundAnswer = "ACCEPTED";
        }
        service.dispatchPending();
        service.checkDue();
        assertEquals(calls, peers.refundCalls.get(), "no blind retry of an unclear refund");
        assertEquals("REFUND_PENDING", peers.statusOf(p.id()));
        refund(merchant, "u2", body(p.id(), 100L)).andExpect(status().isUnprocessableEntity());   // still reserved

        mvc.perform(MockMvcRequestBuilders.post("/internal/v1/refunds/" + id + "/resolve").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FAILED\",\"note\":\"checked PayU dashboard\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FAILED"));
        assertEquals("SUCCESS", peers.statusOf(p.id()));
        mvc.perform(MockMvcRequestBuilders.post("/internal/v1/refunds/" + id + "/resolve").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUCCESS\"}")).andExpect(status().isConflict());
    }

    @Test void providerDownDuringStatusCheckKeepsProcessingWithBackoff() throws Exception {
        FakePeers.Payment p = peers.addPayment(merchant, 3000, "SUCCESS");
        UUID id = create(p, null);
        String req = requestId(id);

        peers.statusAnswer.put(req, "DOWN");
        makeDue(id);
        service.checkDue();
        assertEquals("PROCESSING", dbStatus(id));
        assertEquals(1, jdbc.queryForObject("select check_count from refunds where id = ?", Integer.class, id));
        assertTrue(jdbc.queryForObject("select next_check_at > now() from refunds where id = ?", Boolean.class, id), "backed off");
        assertEquals(0, service.checkDue(), "not due again yet");

        peers.statusAnswer.put(req, "PROCESSING");
        makeDue(id);
        service.checkDue();
        assertEquals("PROCESSING", dbStatus(id), "queued is not success");

        peers.statusAnswer.put(req, "FAILED");
        makeDue(id);
        service.checkDue();
        assertEquals("FAILED", dbStatus(id));
        assertEquals("SUCCESS", peers.statusOf(p.id()));
    }

    @Test void paymentServiceOutageIsRepairedByTheSyncWorker() throws Exception {
        FakePeers.Payment p = peers.addPayment(merchant, 4000, "SUCCESS");
        peers.paymentServiceDown = true;
        UUID id;
        try {
            // creation needs the payment lookup, so it must fail cleanly and leave nothing behind
            refund(merchant, "down1", body(p.id(), null)).andExpect(status().is5xxServerError());
            assertEquals(0, jdbc.queryForObject("select count(*) from refunds where payment_id = ?", Integer.class, p.id()));
        } finally {
            peers.paymentServiceDown = false;
        }
        id = create(p, null);
        peers.statusAnswer.put(requestId(id), "SUCCESS");
        makeDue(id);
        peers.paymentServiceDown = true;
        try {
            service.checkDue();                        // refund settles, payment sync postponed
            assertEquals("SUCCESS", dbStatus(id));
            assertFalse(jdbc.queryForObject("select payment_synced from refunds where id = ?", Boolean.class, id));
        } finally {
            peers.paymentServiceDown = false;
        }
        assertEquals("REFUND_PENDING", peers.statusOf(p.id()));
        service.syncPending();
        assertEquals("REFUNDED", peers.statusOf(p.id()));
        assertTrue(jdbc.queryForObject("select payment_synced from refunds where id = ?", Boolean.class, id));
    }

    @Test void concurrentRequestsCannotRefundMoreThanWasPaid() throws Exception {
        FakePeers.Payment p = peers.addPayment(merchant, 10000, "SUCCESS");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final int n = i;
            Callable<Integer> c = () -> refund(merchant, "race-" + p.id() + "-" + n, body(p.id(), 4000L)).andReturn().getResponse().getStatus();
            results.add(pool.submit(c));
        }
        int accepted = 0;
        for (Future<Integer> f : results) if (f.get() == 202) accepted++;
        pool.shutdown();
        assertEquals(2, accepted, "10000 / 4000 allows exactly two refunds");
        assertEquals(8000L, jdbc.queryForObject("select coalesce(sum(amount),0) from refunds where payment_id = ? and status <> 'FAILED'", Long.class, p.id()));
    }

    @Test void listIsPagedAndFilterable() throws Exception {
        UUID m = UUID.randomUUID();
        FakePeers.Payment p = peers.addPayment(m, 9000, "SUCCESS");
        for (int i = 0; i < 3; i++) {
            refund(m, "l" + i + p.id(), body(p.id(), 1000L)).andExpect(status().isAccepted());
        }
        mvc.perform(api(MockMvcRequestBuilders.get("/api/v1/refunds?size=2"), m)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3)).andExpect(jsonPath("$.data.content.length()").value(2));
        mvc.perform(api(MockMvcRequestBuilders.get("/api/v1/refunds?paymentId=" + UUID.randomUUID()), m)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        JsonNode any = mapper.readTree(mvc.perform(api(MockMvcRequestBuilders.get("/api/v1/refunds"), m)).andReturn().getResponse().getContentAsString());
        assertFalse(any.toString().contains("refund_reference") || any.toString().contains("providerPaymentId"), "provider identifiers stay internal");
    }
}
