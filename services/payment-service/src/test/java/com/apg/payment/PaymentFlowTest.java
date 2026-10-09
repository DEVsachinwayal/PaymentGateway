package com.apg.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.apg.common.api.ApiException;
import com.apg.payment.domain.PaymentStatus;
import com.apg.payment.service.PaymentStateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
@SpringBootTest(properties = {
        "POSTGRES_USER=ignored", "POSTGRES_PASSWORD=ignored", "INTERNAL_SERVICE_TOKEN=internal-token-for-tests-123",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureMockMvc
class PaymentFlowTest {
    static final String TOKEN = "internal-token-for-tests-123";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Stub order-service: orders registered by tests. key = orderId, value = [merchantId, amount, status] */
    static final Map<String, String[]> ORDERS = new ConcurrentHashMap<>();
    static final AtomicInteger orderCalls = new AtomicInteger();
    static final HttpServer orderService = startOrderService();

    static HttpServer startOrderService() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/internal/v1/orders/", ex -> {
                orderCalls.incrementAndGet();
                String id = ex.getRequestURI().getPath().substring("/internal/v1/orders/".length());
                String q = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
                String merchant = q.replace("merchantId=", "");
                String[] o = ORDERS.get(id);
                int code = 200;
                String body;
                if (o == null || !o[0].equals(merchant) || !"INR".equals("INR")) {
                    code = 404;
                    body = "{\"error\":{\"code\":\"ORDER_NOT_FOUND\"}}";
                } else {
                    body = "{\"data\":{\"id\":\"" + id + "\",\"merchantId\":\"" + o[0] + "\",\"amount\":" + o[1]
                            + ",\"currency\":\"INR\",\"status\":\"" + o[2] + "\",\"description\":\"Test order\","
                            + "\"customerName\":\"Asha\",\"customerEmail\":\"asha@example.com\",\"customerPhone\":null}}";
                }
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(code, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("apg.payment.order-service-uri", () -> "http://127.0.0.1:" + orderService.getAddress().getPort());
    }

    @AfterAll
    static void stop() { orderService.stop(0); }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentStateService states;

    private UUID order(UUID merchant, long amount, String status) {
        UUID id = UUID.randomUUID();
        ORDERS.put(id.toString(), new String[]{merchant.toString(), String.valueOf(amount), status});
        return id;
    }

    private MockHttpServletRequestBuilder caller(MockHttpServletRequestBuilder b, UUID merchant) {
        return b.header("X-Internal-Token", TOKEN).header("X-Merchant-Id", merchant.toString())
                .header("X-Auth-Type", "API_KEY").contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions create(UUID merchant, String key, String body) throws Exception {
        MockHttpServletRequestBuilder b = caller(MockMvcRequestBuilders.post("/api/v1/payments"), merchant).content(body);
        if (key != null) b.header("Idempotency-Key", key);
        return mvc.perform(b);
    }

    private ResultActions get(UUID merchant, String path) throws Exception {
        return mvc.perform(caller(MockMvcRequestBuilders.get(path), merchant));
    }

    private ResultActions internalTransition(String id, String to, String source) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/internal/v1/payments/" + id + "/transition")
                .header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"" + to + "\",\"reason\":\"test\",\"source\":\"" + source + "\"}"));
    }

    private JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private String body(UUID orderId) { return "{\"orderId\":\"" + orderId + "\",\"paymentMethod\":\"UPI\"}"; }

    private String createPayment(UUID m, UUID orderId) throws Exception {
        return read(create(m, "k-" + UUID.randomUUID(), body(orderId)).andExpect(status().isCreated())).at("/data/id").asText();
    }

    @Autowired jakarta.persistence.EntityManagerFactory emf;

    /** Guards the N+1 found by the load test: one intent lookup per payment on every list call. */
    @Test void listingPaymentsUsesAConstantNumberOfQueries() throws Exception {
        UUID m = UUID.randomUUID();
        for (int i = 0; i < 12; i++) createPayment(m, order(m, 1000 + i, "CREATED"));
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.clear();
        get(m, "/api/v1/payments?size=20").andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(12));
        assertTrue(stats.getPrepareStatementCount() <= 4, "statements for one page: " + stats.getPrepareStatementCount());
    }

    @Test void amountAndCurrencyComeFromTheOrderAndInitialStateIsCreated() throws Exception {
        UUID m = UUID.randomUUID();
        UUID o = order(m, 75050, "CREATED");
        String id = read(create(m, "k-" + UUID.randomUUID(), body(o)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.amount").value(75050))
                .andExpect(jsonPath("$.data.currency").value("INR"))).at("/data/id").asText();
        get(m, "/api/v1/payments/" + id + "/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.length()").value(1))
                .andExpect(jsonPath("$.data.history[0].to").value("CREATED"));
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?::uuid and event_type='payment.created'",
                Integer.class, id));
    }

    @Test void clientCannotSupplyStatusAmountOrCurrency() throws Exception {
        UUID m = UUID.randomUUID();
        UUID o = order(m, 1000, "CREATED");
        for (String extra : new String[]{"\"status\":\"SUCCESS\"", "\"amount\":1", "\"currency\":\"USD\"", "\"providerPaymentId\":\"x\""}) {
            create(m, "k-" + UUID.randomUUID(), "{\"orderId\":\"" + o + "\"," + extra + "}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from payments where order_id = ?::uuid", Integer.class, o.toString()));
    }

    @Test void orderOwnershipAndPayabilityAreChecked() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        UUID o = order(a, 1000, "CREATED");
        create(b, "k-" + UUID.randomUUID(), body(o)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        UUID paid = order(a, 1000, "PAID");
        create(a, "k-" + UUID.randomUUID(), body(paid)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_PAYABLE"));
        create(a, "k-" + UUID.randomUUID(), body(UUID.randomUUID())).andExpect(status().isNotFound());
        create(a, "k-" + UUID.randomUUID(), "{\"orderId\":\"" + o + "\",\"returnUrl\":\"http://insecure\"}")
                .andExpect(status().isBadRequest());
    }

    @Test void idempotentReplayAndOnlyOneOpenPaymentPerOrder() throws Exception {
        UUID m = UUID.randomUUID();
        UUID o = order(m, 5000, "CREATED");
        String key = "idem-" + UUID.randomUUID();
        String id1 = read(create(m, key, body(o)).andExpect(status().isCreated())).at("/data/id").asText();
        int calls = orderCalls.get();
        ResultActions again = create(m, key, body(o));
        again.andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true"));
        assertEquals(id1, read(again).at("/data/id").asText());
        assertEquals(calls, orderCalls.get(), "replay must not call order-service again");

        // a new idempotency key for the same order must not create a second live payment
        create(m, "other-" + UUID.randomUUID(), body(o)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ALREADY_EXISTS"));
        create(m, null, body(o)).andExpect(status().isBadRequest());
        assertEquals(1, jdbc.queryForObject("select count(*) from payments where order_id = ?::uuid", Integer.class, o.toString()));
    }

    @Test void concurrentCreateForSameOrderYieldsOnePayment() throws Exception {
        UUID m = UUID.randomUUID();
        UUID o = order(m, 5000, "CREATED");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String key = "race-" + i; // different keys => relies on the DB unique index
            fs.add(pool.submit(() -> {
                go.await();
                return create(m, key, body(o)).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        int created = 0;
        for (Future<Integer> f : fs) {
            int s = f.get(30, TimeUnit.SECONDS);
            assertTrue(s == 201 || s == 409, "unexpected status " + s);
            if (s == 201) created++;
        }
        pool.shutdown();
        assertEquals(1, created);
        assertEquals(1, jdbc.queryForObject("select count(*) from payments where order_id = ?::uuid", Integer.class, o.toString()));
    }

    @Test void merchantsAreIsolated() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        String id = createPayment(a, order(a, 1000, "CREATED"));
        get(b, "/api/v1/payments/" + id).andExpect(status().isNotFound());
        get(b, "/api/v1/payments/" + id + "/status").andExpect(status().isNotFound());
        get(b, "/api/v1/payments").andExpect(jsonPath("$.data.totalElements").value(0));
        get(a, "/api/v1/payments").andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test void happyPathTransitionsAreRecordedWithEventsAndInvalidOnesRejected() throws Exception {
        UUID m = UUID.randomUUID();
        String id = createPayment(m, order(m, 1000, "CREATED"));

        internalTransition(id, "SUCCESS", "ORCHESTRATOR").andExpect(status().isConflict())      // CREATED -> SUCCESS impossible
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
        internalTransition(id, "PENDING", "ORCHESTRATOR").andExpect(status().isOk());
        internalTransition(id, "PROCESSING", "ORCHESTRATOR").andExpect(status().isOk());
        internalTransition(id, "SUCCESS", "WEBHOOK").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCESS"));
        internalTransition(id, "SUCCESS", "WEBHOOK").andExpect(status().isOk());                // duplicate webhook: no-op
        internalTransition(id, "FAILED", "WEBHOOK").andExpect(status().isConflict());            // success can't become failed

        get(m, "/api/v1/payments/" + id + "/status").andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.history.length()").value(4));   // CREATED, PENDING, PROCESSING, SUCCESS (duplicate not recorded)
        List<String> events = jdbc.queryForList("select event_type from outbox_events where aggregate_id = ?::uuid order by occurred_at, event_type",
                String.class, id);
        assertTrue(events.containsAll(List.of("payment.created", "payment.processing", "payment.success")), events.toString());
        assertEquals(1, events.stream().filter("payment.success"::equals).count(), "duplicate transition must not emit a second event");
        String payload = jdbc.queryForObject("select payload from outbox_events where event_type='payment.success' and aggregate_id = ?::uuid",
                String.class, id);
        for (String k : new String[]{"eventId", "eventType", "version", "occurredAt", "aggregateId", "merchantId", "payload"}) {
            assertTrue(payload.contains("\"" + k + "\""), "event envelope missing " + k);
        }
    }

    @Test void timeoutGoesToVerifyRequiredAndOnlyVerificationResolvesIt() throws Exception {
        UUID m = UUID.randomUUID();
        String id = createPayment(m, order(m, 1000, "CREATED"));
        internalTransition(id, "PENDING", "ORCHESTRATOR");
        internalTransition(id, "PROCESSING", "ORCHESTRATOR");
        internalTransition(id, "VERIFY_REQUIRED", "ORCHESTRATOR").andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?::uuid and event_type='payment.unknown'",
                Integer.class, id));
        internalTransition(id, "PENDING", "ORCHESTRATOR").andExpect(status().isConflict());     // no blind retry
        internalTransition(id, "SUCCESS", "VERIFICATION").andExpect(status().isOk());
    }

    @Test void failedPaymentFreesTheOrderForANewAttempt() throws Exception {
        UUID m = UUID.randomUUID();
        UUID o = order(m, 1000, "CREATED");
        String first = createPayment(m, o);
        internalTransition(first, "FAILED", "ORCHESTRATOR").andExpect(status().isOk());
        String second = createPayment(m, o);
        assertNotEquals(first, second);
    }

    @Test void concurrentConflictingTransitionsHaveExactlyOneWinner() throws Exception {
        UUID m = UUID.randomUUID();
        String id = createPayment(m, order(m, 1000, "CREATED"));
        internalTransition(id, "PENDING", "ORCHESTRATOR");
        internalTransition(id, "PROCESSING", "ORCHESTRATOR");
        UUID pid = UUID.fromString(id);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<String> toSuccess = () -> { go.await(); return attempt(pid, PaymentStatus.SUCCESS); };
        Callable<String> toFailed = () -> { go.await(); return attempt(pid, PaymentStatus.FAILED); };
        Future<String> a = pool.submit(toSuccess), b = pool.submit(toFailed);
        go.countDown();
        String ra = a.get(30, TimeUnit.SECONDS), rb = b.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue((ra.equals("OK") ^ rb.equals("OK")), "exactly one writer may win: " + ra + " / " + rb);
        String finalStatus = jdbc.queryForObject("select status from payments where id = ?::uuid", String.class, id);
        assertTrue(finalStatus.equals("SUCCESS") || finalStatus.equals("FAILED"));
        // history never records two terminal outcomes
        assertEquals(1, jdbc.queryForObject("select count(*) from payment_state_history where payment_id = ?::uuid and to_status in ('SUCCESS','FAILED')",
                Integer.class, id));
    }

    private String attempt(UUID pid, PaymentStatus to) {
        try {
            states.transition(pid, to, "race", "ORCHESTRATOR");
            return "OK";
        } catch (ApiException e) {
            return e.code();
        }
    }

    private ResultActions internalGet(String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).header("X-Internal-Token", TOKEN));
    }

    private ResultActions beginAttempt(String paymentId, String provider) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/internal/v1/payments/" + paymentId + "/attempts")
                .header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"" + provider + "\"}"));
    }

    @Test void attemptStartsPendingWithUniqueReferenceAndCustomerSnapshot() throws Exception {
        UUID m = UUID.randomUUID();
        String id = createPayment(m, order(m, 4200, "CREATED"));
        internalGet("/internal/v1/payments/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerEmail").value("asha@example.com"))
                .andExpect(jsonPath("$.data.description").value("Test order"))
                .andExpect(jsonPath("$.data.amount").value(4200));

        JsonNode a = read(beginAttempt(id, "payu").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attemptNo").value(1)).andExpect(jsonPath("$.data.paymentStatus").value("PENDING")));
        String ref = a.at("/data/providerReference").asText();
        assertTrue(ref.matches("^A[0-9a-f]{20}$"), ref);
        get(m, "/api/v1/payments/" + id + "/status").andExpect(jsonPath("$.data.status").value("PENDING"));

        beginAttempt(id, "payu").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_STARTABLE"));
        beginAttempt(id, "Bad Provider!").andExpect(status().isBadRequest());

        mvc.perform(MockMvcRequestBuilders.patch("/internal/v1/attempts/" + ref).header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REDIRECTED\",\"providerPaymentId\":\"mihpay-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REDIRECTED"));
        internalGet("/internal/v1/attempts/" + ref).andExpect(jsonPath("$.data.providerPaymentId").value("mihpay-1"))
                .andExpect(jsonPath("$.data.merchantId").value(m.toString()));
        internalGet("/internal/v1/payments/" + id).andExpect(jsonPath("$.data.providerPaymentId").value("mihpay-1"))
                .andExpect(jsonPath("$.data.provider").value("payu"));
        internalGet("/internal/v1/attempts/Anotexisting").andExpect(status().isNotFound());
    }

    @Test void concurrentCheckoutStartsHaveOneWinner() throws Exception {
        UUID m = UUID.randomUUID();
        String id = createPayment(m, order(m, 1000, "CREATED"));
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            fs.add(pool.submit(() -> { go.await(); return beginAttempt(id, "payu").andReturn().getResponse().getStatus(); }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Integer> f : fs) if (f.get(30, TimeUnit.SECONDS) == 200) ok++;
        pool.shutdown();
        assertEquals(1, ok);
        assertEquals(1, jdbc.queryForObject("select count(*) from payment_attempts where payment_id = ?::uuid", Integer.class, id));
    }

    @Test void pendingListShowsStuckPaymentsForTheVerificationWorker() throws Exception {
        UUID m = UUID.randomUUID();
        String id = createPayment(m, order(m, 1000, "CREATED"));
        String ref = read(beginAttempt(id, "payu")).at("/data/providerReference").asText();
        internalTransition(id, "PROCESSING", "ORCHESTRATOR").andExpect(status().isOk());
        String res = internalGet("/internal/v1/payments/pending?statuses=PROCESSING&olderThanSeconds=0&limit=200")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(res.contains(id) && res.contains(ref), "stuck payment should be listed");
        String fresh = internalGet("/internal/v1/payments/pending?statuses=PROCESSING&olderThanSeconds=3600&limit=200")
                .andReturn().getResponse().getContentAsString();
        assertFalse(fresh.contains(id), "recent payments must not be listed yet");
    }

    @Test void internalAndAuthRules() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/internal/v1/payments/" + UUID.randomUUID() + "/transition")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"SUCCESS\",\"source\":\"WEBHOOK\"}"))
                .andExpect(status().isUnauthorized());
        internalTransition(UUID.randomUUID().toString(), "PENDING", "ORCHESTRATOR").andExpect(status().isNotFound());
        internalTransition(UUID.randomUUID().toString(), "PENDING", "MERCHANT").andExpect(status().isBadRequest());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/payments").header("X-Internal-Token", TOKEN)).andExpect(status().isForbidden());
    }
}
