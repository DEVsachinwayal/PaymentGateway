package com.apg.paymentlink;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.apg.paymentlink.service.LinkService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
@SpringBootTest(properties = {"POSTGRES_USER=ignored", "POSTGRES_PASSWORD=ignored", "INTERNAL_SERVICE_TOKEN=internal-token-for-tests-123",
        "apg.paymentlink.public-base-url=https://pay.example.com"})
@AutoConfigureMockMvc
class PaymentLinkFlowTest {
    static final String TOKEN = "internal-token-for-tests-123";
    static final FakePeers peers = new FakePeers();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("apg.paymentlink.merchant-service-uri", peers::baseUrl);
        r.add("apg.paymentlink.order-service-uri", peers::baseUrl);
        r.add("apg.paymentlink.payment-service-uri", peers::baseUrl);
        r.add("apg.paymentlink.orchestrator-uri", peers::baseUrl);
    }

    @AfterAll
    static void stop() { peers.close(); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired LinkService service;

    private final UUID merchant = UUID.randomUUID();

    @BeforeEach
    void reset() {
        peers.merchantName = "Asha's Books";
        peers.merchantStatus = "SANDBOX";
        peers.orderServiceDown = false;
        peers.orderDelayMillis = 0;
    }

    private MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder b, UUID m) {
        return b.header("X-Internal-Token", TOKEN).header("X-Merchant-Id", m.toString()).header("X-Auth-Type", "API_KEY").contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions create(UUID m, String key, String json) throws Exception {
        MockHttpServletRequestBuilder b = api(MockMvcRequestBuilders.post("/api/v1/payment-links"), m).content(json);
        if (key != null) b.header("Idempotency-Key", key);
        return mvc.perform(b);
    }

    private JsonNode createOk(UUID m, String json) throws Exception {
        return mapper.readTree(create(m, "k-" + UUID.randomUUID(), json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private static final String BODY = "{\"amount\":49900,\"currency\":\"INR\",\"description\":\"Learn Payments\"}";

    private ResultActions page(String code) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/pay/" + code).header("X-Internal-Token", TOKEN));
    }

    private ResultActions pay(String code) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/pay/" + code + "/start").header("X-Internal-Token", TOKEN));
    }

    private UUID paymentOf(String code) {
        return jdbc.queryForObject("select a.payment_id from link_attempts a join payment_links l on l.id = a.link_id where l.code = ? and a.payment_id is not null order by a.created_at desc limit 1", UUID.class, code);
    }

    private String statusOf(String code) { return jdbc.queryForObject("select status from payment_links where code = ?", String.class, code); }

    // ------------------------------------------------------------------ merchant API

    @Test void createReturnsAStablePublicUrlAndIsIdempotent() throws Exception {
        String key = "k-" + UUID.randomUUID();
        JsonNode a = mapper.readTree(create(merchant, key, BODY).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
        JsonNode b = mapper.readTree(create(merchant, key, BODY).andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(a.path("id"), b.path("id"));
        assertTrue(a.path("url").asText().matches("^https://pay\\.example\\.com/pay/[2-9a-zA-Z]{10}$"), a.path("url").asText());
        assertEquals("ACTIVE", a.path("status").asText());
        assertTrue(a.path("oneTime").asBoolean());
        assertEquals(1, jdbc.queryForObject("select count(*) from payment_links where merchant_id = ?", Integer.class, merchant) > 0 ? 1 : 0);
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_events where action = 'PAYMENT_LINK_CREATED' and target = ?", Integer.class, a.path("id").asText()));
        create(merchant, key, BODY.replace("49900", "49901")).andExpect(status().isUnprocessableEntity());
    }

    @Test void inputIsValidatedStrictly() throws Exception {
        create(merchant, null, BODY).andExpect(status().isBadRequest());
        create(merchant, "v1", "{\"amount\":0,\"currency\":\"INR\"}").andExpect(status().isBadRequest());
        create(merchant, "v2", "{\"amount\":-5,\"currency\":\"INR\"}").andExpect(status().isBadRequest());
        create(merchant, "v3", "{\"amount\":10.5,\"currency\":\"INR\"}").andExpect(status().isBadRequest());
        create(merchant, "v4", "{\"amount\":100,\"currency\":\"inr\"}").andExpect(status().isBadRequest());
        create(merchant, "v5", "{\"amount\":100,\"currency\":\"INR\",\"status\":\"PAID\"}").andExpect(status().isBadRequest());
        create(merchant, "v6", "{\"amount\":100,\"currency\":\"INR\",\"returnUrl\":\"http://shop.example/x\"}").andExpect(status().isBadRequest());
        create(merchant, "v7", "{\"amount\":100,\"currency\":\"INR\",\"expiresInHours\":0}").andExpect(status().isBadRequest());
        create(merchant, "v8", "{\"amount\":100,\"currency\":\"INR\",\"expiresInHours\":99999}").andExpect(status().isBadRequest());
        create(merchant, "v9", "{\"amount\":999999999999,\"currency\":\"INR\"}").andExpect(status().isBadRequest());
        peers.merchantStatus = "SUSPENDED";
        create(merchant, "v10", BODY).andExpect(status().isForbidden());
    }

    @Test void merchantsSeeOnlyTheirOwnLinksAndRolesAreEnforced() throws Exception {
        UUID other = UUID.randomUUID();
        JsonNode mine = createOk(merchant, BODY);
        api(MockMvcRequestBuilders.get("/api/v1/payment-links/" + mine.path("id").asText()), other).with(r -> r);
        mvc.perform(api(MockMvcRequestBuilders.get("/api/v1/payment-links/" + mine.path("id").asText()), other)).andExpect(status().isNotFound());
        mvc.perform(api(MockMvcRequestBuilders.post("/api/v1/payment-links/" + mine.path("id").asText() + "/cancel"), other)).andExpect(status().isNotFound());
        mvc.perform(api(MockMvcRequestBuilders.get("/api/v1/payment-links?size=100"), other)).andExpect(jsonPath("$.data.totalElements").value(0));
        MockHttpServletRequestBuilder dev = MockMvcRequestBuilders.post("/api/v1/payment-links").header("X-Internal-Token", TOKEN).header("X-Auth-Type", "USER")
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Roles", "MERCHANT_DEVELOPER").header("X-Merchant-Id", merchant.toString())
                .header("Idempotency-Key", "d1").contentType(MediaType.APPLICATION_JSON).content(BODY);
        mvc.perform(dev).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/payment-links")).andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/payment-links").header("X-Internal-Token", TOKEN)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ customer page

    @Test void publicPageShowsOnlyWhatACustomerNeedsAndEscapesMerchantText() throws Exception {
        peers.merchantName = "<script>alert(1)</script> & Co";
        JsonNode link = createOk(merchant, "{\"amount\":49900,\"currency\":\"INR\",\"description\":\"<img src=x onerror=alert(1)>\"}");
        String code = link.path("code").asText();
        var res = page(code).andExpect(status().isOk()).andReturn().getResponse();
        String html = res.getContentAsString();
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; Co"), html);
        assertFalse(html.contains("<script>") || html.contains("<img"), "merchant text must be escaped");
        assertTrue(html.contains("499.00"), "amount is shown");
        assertTrue(html.contains("action=\"/pay/" + code + "/start\""));
        assertFalse(html.contains(merchant.toString()) || html.contains(link.path("id").asText()), "no internal ids on the public page");
        String csp = res.getHeader("Content-Security-Policy");
        assertTrue(csp.contains("default-src 'none'") && csp.contains("nonce-") && !csp.contains("unsafe-inline"), csp);
        assertEquals("no-store", res.getHeader("Cache-Control"));
        assertEquals("DENY", res.getHeader("X-Frame-Options"));
    }

    @Test void unknownOrMalformedCodesAre404WithoutLeakingAnything() throws Exception {
        page("AAAAAAAAAA").andExpect(status().isNotFound());
        page("x").andExpect(status().isNotFound());
        page("a'%20or%201=1--").andExpect(status().isNotFound());
        pay("AAAAAAAAAA").andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ paying

    @Test void payingStartsAnOrderAndPaymentFromTheStoredLinkAndRedirectsToCheckout() throws Exception {
        String code = createOk(merchant, "{\"amount\":49900,\"currency\":\"INR\",\"description\":\"Learn Payments\",\"customer\":{\"name\":\"Ravi\",\"email\":\"ravi@example.com\"}}").path("code").asText();
        int before = peers.orderCalls.size();
        String location = pay(code).andExpect(status().isSeeOther()).andReturn().getResponse().getHeader("Location");
        assertTrue(location.startsWith("https://checkout.test/c/"), location);
        FakePeers.OrderCall call = peers.orderCalls.get(before);
        assertEquals(merchant.toString(), call.merchantHeader(), "merchant comes from the link");
        assertEquals("API_KEY", call.authType());
        assertEquals(49900, call.body().path("amount").asLong());
        assertEquals("INR", call.body().path("currency").asText());
        assertEquals("Ravi", call.body().path("customer").path("name").asText());
        assertTrue(call.body().path("merchantOrderRef").asText().startsWith("LINK-" + code + "-"));
        assertTrue(call.idemKey().startsWith("plink-"));
        assertEquals("OPEN", jdbc.queryForObject("select a.state from link_attempts a join payment_links l on l.id = a.link_id where l.code = ? order by a.created_at desc limit 1", String.class, code));
    }

    @Test void theCustomerCannotChangeTheAmountOrTheMerchant() throws Exception {
        String code = createOk(merchant, BODY).path("code").asText();
        int before = peers.orderCalls.size();
        mvc.perform(MockMvcRequestBuilders.post("/pay/" + code + "/start?amount=1&currency=USD&merchantId=" + UUID.randomUUID()).header("X-Internal-Token", TOKEN)
                .header("X-Merchant-Id", UUID.randomUUID().toString()).param("amount", "1")).andExpect(status().isSeeOther());
        FakePeers.OrderCall call = peers.orderCalls.get(before);
        assertEquals(49900, call.body().path("amount").asLong());
        assertEquals("INR", call.body().path("currency").asText());
        assertEquals(merchant.toString(), call.merchantHeader());
    }

    @Test void aFailedStartChargesNothingAndCanBeRetried() throws Exception {
        String code = createOk(merchant, BODY).path("code").asText();
        peers.orderServiceDown = true;
        pay(code).andExpect(status().isBadGateway());
        assertEquals("START_FAILED", jdbc.queryForObject("select a.state from link_attempts a join payment_links l on l.id = a.link_id where l.code = ? order by a.created_at desc limit 1", String.class, code));
        peers.orderServiceDown = false;
        pay(code).andExpect(status().isSeeOther());
        assertEquals("ACTIVE", statusOf(code));
    }

    @Test void oneTimeLinkAllowsRetryBeforeTheProviderButNotWhileAPaymentIsAtTheProvider() throws Exception {
        String code = createOk(merchant, BODY).path("code").asText();
        pay(code).andExpect(status().isSeeOther());
        jdbc.update("update link_attempts set created_at = now() - interval '3 minutes' where link_id = (select id from payment_links where code = ?)", code);
        pay(code).andExpect(status().isSeeOther());             // the first customer never reached the provider (payment still CREATED)

        peers.paymentStatus.put(paymentOf(code), "PROCESSING");  // now one is at the provider
        pay(code).andExpect(status().isConflict());
        page(code).andExpect(status().isOk());
        peers.paymentStatus.put(paymentOf(code), "FAILED");      // the customer failed: they may try again
        pay(code).andExpect(status().isSeeOther());
    }

    @Test void parallelPressesOnAOneTimeLinkCreateOnlyOneOrder() throws Exception {
        String code = createOk(merchant, BODY).path("code").asText();
        peers.orderDelayMillis = 400;
        int before = peers.orders.get();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) fs.add(pool.submit(() -> pay(code).andReturn().getResponse().getStatus()));
        int redirected = 0, blocked = 0;
        for (Future<Integer> f : fs) { int s = f.get(); if (s == 303) redirected++; else if (s == 409) blocked++; }
        pool.shutdown();
        assertEquals(1, redirected, "exactly one attempt may start");
        assertEquals(5, blocked);
        assertEquals(before + 1, peers.orders.get());
    }

    // ------------------------------------------------------------------ the link becomes paid only from real payment state

    @Test void aConfirmedPaymentMarksAOneTimeLinkPaidExactlyOnce() throws Exception {
        String code = createOk(merchant, BODY).path("code").asText();
        pay(code).andExpect(status().isSeeOther());
        UUID payment = paymentOf(code);
        service.paymentSucceeded(payment);
        service.paymentSucceeded(payment);                       // duplicate event
        assertEquals("PAID", statusOf(code));
        assertEquals(1, jdbc.queryForObject("select successful_payments from payment_links where code = ?", Integer.class, code));
        page(code).andExpect(status().isGone()).andExpect(content().string(org.hamcrest.Matchers.containsString("already been paid")));
        pay(code).andExpect(status().isGone());
    }

    @Test void theLinkNoticesAPaymentEvenIfTheEventWasLost() throws Exception {
        String code = createOk(merchant, BODY).path("code").asText();
        pay(code).andExpect(status().isSeeOther());
        peers.paymentStatus.put(paymentOf(code), "SUCCESS");
        page(code).andExpect(status().isGone());                 // opening the page asks payment-service
        assertEquals("PAID", statusOf(code));
    }

    @Test void aReusableLinkKeepsWorkingAndCountsEveryPayment() throws Exception {
        String code = createOk(merchant, "{\"amount\":1000,\"currency\":\"INR\",\"oneTime\":false}").path("code").asText();
        pay(code).andExpect(status().isSeeOther());
        service.paymentSucceeded(paymentOf(code));
        pay(code).andExpect(status().isSeeOther());
        service.paymentSucceeded(paymentOf(code));
        assertEquals("ACTIVE", statusOf(code));
        assertEquals(2, jdbc.queryForObject("select successful_payments from payment_links where code = ?", Integer.class, code));
        page(code).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ expiry and cancel

    @Test void expiredAndCancelledLinksCannotBePaid() throws Exception {
        JsonNode l1 = createOk(merchant, BODY);
        jdbc.update("update payment_links set expires_at = ? where code = ?", Timestamp.from(Instant.now().minusSeconds(5)), l1.path("code").asText());
        page(l1.path("code").asText()).andExpect(status().isGone()).andExpect(content().string(org.hamcrest.Matchers.containsString("expired")));
        pay(l1.path("code").asText()).andExpect(status().isGone());
        mvc.perform(api(MockMvcRequestBuilders.get("/api/v1/payment-links/" + l1.path("id").asText()), merchant)).andExpect(jsonPath("$.data.status").value("EXPIRED"));

        JsonNode l2 = createOk(merchant, BODY);
        mvc.perform(api(MockMvcRequestBuilders.post("/api/v1/payment-links/" + l2.path("id").asText() + "/cancel"), merchant)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        pay(l2.path("code").asText()).andExpect(status().isGone());
        mvc.perform(api(MockMvcRequestBuilders.post("/api/v1/payment-links/" + l2.path("id").asText() + "/cancel"), merchant)).andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_events where action = 'PAYMENT_LINK_CANCELLED' and target = ?", Integer.class, l2.path("id").asText()));
    }

    @Test void listFiltersByStatus() throws Exception {
        UUID m = UUID.randomUUID();
        JsonNode a = createOk(m, BODY);
        createOk(m, BODY);
        mvc.perform(api(MockMvcRequestBuilders.post("/api/v1/payment-links/" + a.path("id").asText() + "/cancel"), m)).andExpect(status().isOk());
        mvc.perform(api(MockMvcRequestBuilders.get("/api/v1/payment-links?status=ACTIVE"), m)).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(api(MockMvcRequestBuilders.get("/api/v1/payment-links?status=CANCELLED"), m)).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(api(MockMvcRequestBuilders.get("/api/v1/payment-links?status=BANANA"), m)).andExpect(status().isBadRequest());
    }
}
