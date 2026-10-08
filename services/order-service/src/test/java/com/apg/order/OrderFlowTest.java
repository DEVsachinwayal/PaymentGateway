package com.apg.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "POSTGRES_USER=ignored", "POSTGRES_PASSWORD=ignored", "INTERNAL_SERVICE_TOKEN=internal-token-for-tests-123"})
@AutoConfigureMockMvc
class OrderFlowTest {
    static final String TOKEN = "internal-token-for-tests-123";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private MockHttpServletRequestBuilder apiKeyCaller(MockHttpServletRequestBuilder b, UUID merchant) {
        return b.header("X-Internal-Token", TOKEN).header("X-Merchant-Id", merchant.toString())
                .header("X-Auth-Type", "API_KEY").contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions create(UUID merchant, String idemKey, String body) throws Exception {
        MockHttpServletRequestBuilder b = apiKeyCaller(MockMvcRequestBuilders.post("/api/v1/orders"), merchant).content(body);
        if (idemKey != null) b.header("Idempotency-Key", idemKey);
        return mvc.perform(b);
    }

    private ResultActions get(UUID merchant, String path) throws Exception {
        return mvc.perform(apiKeyCaller(MockMvcRequestBuilders.get(path), merchant));
    }

    private JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    static final String ORDER = """
            {"merchantOrderRef":"%s","amount":50000,"currency":"INR","description":"Two books",
             "customer":{"name":"Asha","email":"asha@example.com","phone":"+919876543210"},
             "items":[{"name":"Book A","quantity":2,"unitAmount":20000},{"name":"Shipping","quantity":1,"unitAmount":10000}],
             "metadata":{"cart":"c-42"}}""";

    private String order(String ref) { return ORDER.formatted(ref); }

    @Test void createsAndFetchesOrderWithIntegerMinorUnits() throws Exception {
        UUID m = UUID.randomUUID();
        JsonNode created = read(create(m, "idem-" + UUID.randomUUID(), order("R-" + UUID.randomUUID()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.amount").value(50000)).andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.customer.email").value("asha@example.com"))
                .andExpect(jsonPath("$.data.metadata.cart").value("c-42")));
        String id = created.at("/data/id").asText();
        get(m, "/api/v1/orders/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.data.amount").value(50000));
        get(m, "/api/v1/orders").andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test void sameKeyAndBodyReplaysSameOrderDifferentBodyRejectedMissingKeyRejected() throws Exception {
        UUID m = UUID.randomUUID();
        String key = "k-" + UUID.randomUUID();
        String body = order("R-" + UUID.randomUUID());
        String id1 = read(create(m, key, body).andExpect(status().isCreated())).at("/data/id").asText();
        ResultActions again = create(m, key, body);
        again.andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true"));
        assertEquals(id1, read(again).at("/data/id").asText());
        assertEquals(1, jdbc.queryForObject("select count(*) from orders where merchant_id = ?::uuid", Integer.class, m.toString()));

        create(m, key, body.replace("50000", "50001").replace("20000", "20001"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        create(m, null, order("R-" + UUID.randomUUID())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test void concurrentDuplicateRequestsCreateExactlyOneOrder() throws Exception {
        UUID m = UUID.randomUUID();
        String key = "race-" + UUID.randomUUID();
        String body = order("R-" + UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        java.util.List<Future<String>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return read(create(m, key, body).andExpect(status().isCreated())).at("/data/id").asText();
            }));
        }
        go.countDown();
        String first = fs.get(0).get(30, TimeUnit.SECONDS);
        for (Future<String> f : fs) assertEquals(first, f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(1, jdbc.queryForObject("select count(*) from orders where merchant_id = ?::uuid", Integer.class, m.toString()));
    }

    @Test void merchantsCannotSeeEachOthersOrders() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        String id = read(create(a, "k-" + UUID.randomUUID(), order("R-" + UUID.randomUUID()))).at("/data/id").asText();
        get(b, "/api/v1/orders/" + id).andExpect(status().isNotFound());
        get(b, "/api/v1/orders").andExpect(jsonPath("$.data.totalElements").value(0));
        // same merchantOrderRef is allowed for different merchants
        String ref = "SHARED-" + UUID.randomUUID();
        create(a, "k1-" + UUID.randomUUID(), order(ref)).andExpect(status().isCreated());
        create(b, "k2-" + UUID.randomUUID(), order(ref)).andExpect(status().isCreated());
        create(a, "k3-" + UUID.randomUUID(), order(ref)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_ORDER_REF"));
    }

    @Test void moneyValidation() throws Exception {
        UUID m = UUID.randomUUID();
        String base = "{\"amount\":%s,\"currency\":\"%s\"%s}";
        create(m, "a" + UUID.randomUUID(), base.formatted("0", "INR", "")).andExpect(status().isBadRequest());
        create(m, "b" + UUID.randomUUID(), base.formatted("-5", "INR", "")).andExpect(status().isBadRequest());
        create(m, "c" + UUID.randomUUID(), base.formatted("49.99", "INR", "")).andExpect(status().isBadRequest()); // no silent truncation
        create(m, "d" + UUID.randomUUID(), base.formatted("100", "USD", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CURRENCY_NOT_SUPPORTED"));
        create(m, "e" + UUID.randomUUID(), base.formatted("100000000001", "INR", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AMOUNT_TOO_LARGE"));
        create(m, "f" + UUID.randomUUID(), base.formatted("100", "INR",
                ",\"items\":[{\"name\":\"x\",\"quantity\":2,\"unitAmount\":40}]")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AMOUNT_MISMATCH"));
        create(m, "g" + UUID.randomUUID(), base.formatted("100", "INR", ",\"customer\":{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertEquals(0, jdbc.queryForObject("select count(*) from orders where merchant_id = ?::uuid", Integer.class, m.toString()));
        assertEquals(0, jdbc.queryForObject("select count(*) from idempotency_keys where merchant_id = ?::uuid", Integer.class, m.toString()),
                "failed requests must not leave idempotency markers");
    }

    @Test void internalLookupIsTokenProtectedAndMerchantScoped() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        String id = read(create(a, "k-" + UUID.randomUUID(), order("R-" + UUID.randomUUID()))).at("/data/id").asText();
        mvc.perform(MockMvcRequestBuilders.get("/internal/v1/orders/" + id).param("merchantId", a.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.get("/internal/v1/orders/" + id).param("merchantId", a.toString())
                .header("X-Internal-Token", TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(50000)).andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.customerEmail").value("asha@example.com"))
                .andExpect(jsonPath("$.data.customerName").value("Asha"))
                .andExpect(jsonPath("$.data.description").value("Two books"));
        mvc.perform(MockMvcRequestBuilders.get("/internal/v1/orders/" + id).param("merchantId", b.toString())
                .header("X-Internal-Token", TOKEN)).andExpect(status().isNotFound());
    }

    @Test void authorizationRules() throws Exception {
        UUID m = UUID.randomUUID();
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/orders").header("X-Internal-Token", TOKEN)).andExpect(status().isForbidden());
        // dashboard user: allowed with merchant role, denied with an unrelated role
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/orders").header("X-Internal-Token", TOKEN)
                .header("X-Auth-Type", "USER").header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Roles", "MERCHANT_DEVELOPER").header("X-Merchant-Id", m.toString())).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/orders").header("X-Internal-Token", TOKEN)
                .header("X-Auth-Type", "USER").header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Roles", "APG_FINANCE").header("X-Merchant-Id", m.toString())).andExpect(status().isForbidden());
        // user with no merchant yet
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/orders").header("X-Internal-Token", TOKEN)
                .header("X-Auth-Type", "USER").header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Roles", "MERCHANT_ADMIN")).andExpect(status().isForbidden());
    }
}
