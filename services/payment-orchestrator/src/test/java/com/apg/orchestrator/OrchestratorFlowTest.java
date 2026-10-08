package com.apg.orchestrator;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.apg.orchestrator.FakeBackends.P;
import com.apg.orchestrator.service.VerificationWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {"POSTGRES_USER=ignored", "POSTGRES_PASSWORD=ignored",
        "INTERNAL_SERVICE_TOKEN=internal-token-for-tests-123", "CHECKOUT_TOKEN_SECRET=checkout-secret-for-tests-1234",
        "apg.orchestrator.verification.enabled=false"})
@AutoConfigureMockMvc
@Import(OrchestratorFlowTest.TestClock.class)
class OrchestratorFlowTest {
    static final String TOKEN = "internal-token-for-tests-123";

    static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-09T10:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    static final MutableClock clock = new MutableClock();
    static final FakeBackends fake = new FakeBackends(clock);

    @TestConfiguration
    static class TestClock {
        @Bean @Primary Clock testClock() { return clock; }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("apg.orchestrator.payment-service-uri", fake::baseUrl);
        r.add("apg.orchestrator.provider-service-uri", fake::baseUrl);
        r.add("apg.orchestrator.public-base-url", () -> "https://pay.apg.example");
    }

    @AfterAll
    static void stop() { fake.close(); }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired VerificationWorker worker;

    private ResultActions checkout(UUID merchant, String paymentId, String idemKey) throws Exception {
        var b = MockMvcRequestBuilders.post("/api/v1/payments/" + paymentId + "/checkout").header("X-Internal-Token", TOKEN)
                .header("X-Merchant-Id", merchant.toString()).header("X-Auth-Type", "API_KEY").contentType(MediaType.APPLICATION_JSON);
        if (idemKey != null) b.header("Idempotency-Key", idemKey);
        return mvc.perform(b);
    }

    private ResultActions verify(String ref, String trigger) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/internal/v1/orchestrator/attempts/" + ref + "/verify")
                .header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"trigger\":\"" + trigger + "\"}"));
    }

    private JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    /** payment moved to PROCESSING as if the customer had been redirected */
    private P started(UUID m, long amount) throws Exception {
        P p = fake.newPayment(m.toString(), amount);
        checkout(m, p.id, "k-" + UUID.randomUUID()).andExpect(status().isCreated());
        return p;
    }

    private String tokenOf(JsonNode checkoutResponse) {
        String url = checkoutResponse.at("/data/checkoutUrl").asText();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static String ok(long minor) { return "{\"status\":\"SUCCESS\",\"providerPaymentId\":\"mih-1\",\"amountMinor\":" + minor + "}"; }

    // ---------------------------------------------------------------- checkout
    @Test void checkoutBeginsAttemptRecordsRoutingAndReturnsHostedUrl() throws Exception {
        UUID m = UUID.randomUUID();
        P p = fake.newPayment(m.toString(), 12345);
        JsonNode r = read(checkout(m, p.id, "k1").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING")).andExpect(jsonPath("$.data.provider").value("payu")));
        assertTrue(r.at("/data/checkoutUrl").asText().startsWith("https://pay.apg.example/checkout/"));
        assertEquals("PENDING", p.status);
        assertNotNull(p.reference);
        assertEquals(1, jdbc.queryForObject("select count(*) from routing_decisions where payment_id = ?::uuid and provider='payu'", Integer.class, p.id));
        assertTrue(jdbc.queryForObject("select candidates from routing_decisions where payment_id = ?::uuid", String.class, p.id).contains("payu(priority=1"));
    }

    @Test void checkoutIsIdempotentOwnedAndOnlyFromCreated() throws Exception {
        UUID m = UUID.randomUUID();
        P p = fake.newPayment(m.toString(), 1000);
        String url1 = read(checkout(m, p.id, "same")).at("/data/checkoutUrl").asText();
        ResultActions again = checkout(m, p.id, "same");
        again.andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true"));
        assertEquals(url1, read(again).at("/data/checkoutUrl").asText());
        assertEquals(1, p.history.stream().filter("CREATED->PENDING"::equals).count());

        checkout(m, p.id, "different-key").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_STARTABLE"));
        checkout(m, p.id, null).andExpect(status().isBadRequest());

        P other = fake.newPayment(UUID.randomUUID().toString(), 1000);
        checkout(m, other.id, "k-x").andExpect(status().isNotFound());
        assertEquals("CREATED", other.status, "a foreign merchant must not be able to start someone else's payment");
    }

    @Test void providerTroubleBeforeRedirectFailsThePaymentSafely() throws Exception {
        UUID m = UUID.randomUUID();
        P p = fake.newPayment(m.toString(), 1000);
        fake.failCheckout = true;
        try {
            checkout(m, p.id, "k-fail").andExpect(status().isBadGateway());
        } finally {
            fake.failCheckout = false;
        }
        assertEquals("FAILED", p.status);
        assertEquals("FAILED", p.attemptStatus);
    }

    @Test void noHealthyProviderMeansNoAttempt() throws Exception {
        UUID m = UUID.randomUUID();
        P p = fake.newPayment(m.toString(), 1000);
        String saved = fake.providersJson;
        fake.providersJson = saved.replace("\"up\":true", "\"up\":false");
        try {
            checkout(m, p.id, "k-down").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("NO_PROVIDER_AVAILABLE"));
        } finally {
            fake.providersJson = saved;
        }
        assertEquals("CREATED", p.status);
        assertNull(p.reference);
    }

    @Test void routingPrefersLowestPriorityHealthyProvider() throws Exception {
        UUID m = UUID.randomUUID();
        P p = fake.newPayment(m.toString(), 1000);
        String saved = fake.providersJson;
        fake.providersJson = "[{\"provider\":\"other\",\"enabled\":true,\"mode\":\"TEST\",\"priority\":0,\"health\":{\"up\":false}},"
                + saved.substring(1);
        try {
            checkout(m, p.id, "k-route").andExpect(status().isCreated()).andExpect(jsonPath("$.data.provider").value("payu"));
        } finally {
            fake.providersJson = saved;
        }
    }

    // ---------------------------------------------------------------- hosted redirect page
    @Test void redirectPageRendersEscapedFormAndMovesPaymentToProcessing() throws Exception {
        UUID m = UUID.randomUUID();
        P p = fake.newPayment(m.toString(), 1000);
        String token = tokenOf(read(checkout(m, p.id, "k-page")));

        String html = mvc.perform(MockMvcRequestBuilders.get("/checkout/" + token).header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("action=\"https://test.payu.in/_payment\""));
        assertTrue(html.contains("name=\"hash\" value=\"abc\""));
        assertTrue(html.contains("A &quot;quoted&quot; &lt;b&gt;item&lt;/b&gt;"), "provider values must be HTML-escaped");
        assertFalse(html.contains("<b>item</b>"));
        assertEquals("PROCESSING", p.status);
        assertEquals("REDIRECTED", p.attemptStatus);

        // refreshing the page is harmless
        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + token).header("X-Internal-Token", TOKEN)).andExpect(status().isOk());
        assertEquals(1, p.history.stream().filter("PENDING->PROCESSING"::equals).count());
    }

    @Test void forgedExpiredAndClosedLinksAreRejected() throws Exception {
        UUID m = UUID.randomUUID();
        P p = fake.newPayment(m.toString(), 1000);
        String token = tokenOf(read(checkout(m, p.id, "k-tok")));
        String[] parts = token.split("\\.");

        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + parts[0] + "." + parts[1] + ".AAAA").header("X-Internal-Token", TOKEN))
                .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get("/checkout/garbage").header("X-Internal-Token", TOKEN)).andExpect(status().isNotFound());

        fake.providerStatus.put(p.reference, ok(1000));
        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + token).header("X-Internal-Token", TOKEN)).andExpect(status().isOk());
        verify(p.reference, "WEBHOOK").andExpect(status().isOk());
        assertEquals("SUCCESS", p.status);
        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + token).header("X-Internal-Token", TOKEN))
                .andExpect(status().isConflict());      // already paid: cannot be paid twice via the link

        clock.advance(Duration.ofHours(2));
        try {
            P q = fake.newPayment(m.toString(), 1000);
            // token created "now" expires 30 min later; simulate by issuing then advancing
            String t2 = tokenOf(read(checkout(m, q.id, "k-tok2")));
            clock.advance(Duration.ofHours(1));
            mvc.perform(MockMvcRequestBuilders.get("/checkout/" + t2).header("X-Internal-Token", TOKEN)).andExpect(status().isGone());
        } finally {
            clock.advance(Duration.ofHours(1));
        }
    }

    // ---------------------------------------------------------------- verification
    @Test void verifiedSuccessSettlesPaymentThroughProcessing() throws Exception {
        UUID m = UUID.randomUUID();
        P p = started(m, 5000);                       // PENDING
        fake.providerStatus.put(p.reference, ok(5000));
        verify(p.reference, "WEBHOOK").andExpect(jsonPath("$.data.result").value("SETTLED_SUCCESS"));
        assertEquals("SUCCESS", p.status);
        assertEquals("SUCCESS", p.attemptStatus);
        assertEquals("mih-1", p.providerPaymentId);
        assertEquals(List.of("CREATED->PENDING", "PENDING->PROCESSING", "PROCESSING->SUCCESS"), p.history);
        verify(p.reference, "WEBHOOK").andExpect(jsonPath("$.data.result").value("NO_CHANGE"));      // duplicate webhook
        assertEquals(3, p.history.size());
        assertEquals(2, jdbc.queryForObject("select count(*) from verification_log where attempt_reference = ?", Integer.class, p.reference));
    }

    @Test void providerAmountMismatchNeverSettlesAsSuccess() throws Exception {
        UUID m = UUID.randomUUID();
        P p = started(m, 5000);
        fake.providerStatus.put(p.reference, ok(4999));
        verify(p.reference, "WEBHOOK").andExpect(jsonPath("$.data.result").value("AMOUNT_MISMATCH"));
        assertEquals("VERIFY_REQUIRED", p.status);
    }

    @Test void explicitProviderFailureFailsThePayment() throws Exception {
        UUID m = UUID.randomUUID();
        P p = started(m, 5000);
        fake.providerStatus.put(p.reference, "{\"status\":\"FAILED\",\"errorCode\":\"E1204\"}");
        verify(p.reference, "WEBHOOK").andExpect(jsonPath("$.data.result").value("SETTLED_FAILED"));
        assertEquals("FAILED", p.status);
    }

    @Test void providerOutageOrTimeoutBecomesVerifyRequiredNeverFailed() throws Exception {
        UUID m = UUID.randomUUID();
        P p = started(m, 5000);
        fake.providerStatus.put(p.reference, "502");
        verify(p.reference, "VERIFICATION").andExpect(jsonPath("$.data.result").value("MARKED_UNKNOWN"));
        assertEquals("VERIFY_REQUIRED", p.status);
        verify(p.reference, "VERIFICATION").andExpect(jsonPath("$.data.result").value("MARKED_UNKNOWN"));
        assertEquals("VERIFY_REQUIRED", p.status, "stays unknown, no blind retry/failure");
        // provider recovers and says success
        fake.providerStatus.put(p.reference, ok(5000));
        verify(p.reference, "VERIFICATION").andExpect(jsonPath("$.data.result").value("SETTLED_SUCCESS"));
        assertEquals("SUCCESS", p.status);
    }

    @Test void pendingAtProviderStaysUntilTimeoutThenNeedsVerification() throws Exception {
        UUID m = UUID.randomUUID();
        P p = started(m, 5000);
        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + tokenOfLast(p)).header("X-Internal-Token", TOKEN));   // -> PROCESSING
        assertEquals("PROCESSING", p.status);
        fake.providerStatus.put(p.reference, "{\"status\":\"PENDING\"}");
        verify(p.reference, "VERIFICATION").andExpect(jsonPath("$.data.result").value("STILL_PENDING"));
        assertEquals("PROCESSING", p.status);
        clock.advance(Duration.ofMinutes(16));
        verify(p.reference, "VERIFICATION").andExpect(jsonPath("$.data.result").value("MARKED_UNKNOWN"));
        assertEquals("VERIFY_REQUIRED", p.status);
    }

    private String tokenOfLast(P p) throws Exception {
        // issue a fresh token for the same attempt via the public service path (the checkout response is not stored in the fake)
        return new com.apg.orchestrator.service.CheckoutTokens(
                new com.apg.orchestrator.service.OrchestratorProperties("https://x", "checkout-secret-for-tests-1234", Duration.ofMinutes(30), "", "", null),
                clock).issue(p.reference).token();
    }

    @Test void providerNeverSeeingTheTransactionFailsOnlyAfterGracePeriod() throws Exception {
        UUID m = UUID.randomUUID();
        P p = started(m, 5000);                                  // PENDING, provider says NOT_FOUND
        verify(p.reference, "VERIFICATION").andExpect(jsonPath("$.data.result").value("STILL_PENDING"));
        assertEquals("PENDING", p.status);
        clock.advance(Duration.ofMinutes(31));
        verify(p.reference, "VERIFICATION").andExpect(jsonPath("$.data.result").value("SETTLED_FAILED"));
        assertEquals("FAILED", p.status);
    }

    @Test void workerVerifiesStuckPaymentsAndSurvivesBadOnes() throws Exception {
        UUID m = UUID.randomUUID();
        P good = started(m, 7000);
        P flaky = started(m, 7100);
        fake.providerStatus.put(good.reference, ok(7000));
        fake.providerStatus.put(flaky.reference, "502");
        int n = worker.runOnce();
        assertTrue(n >= 2);
        assertEquals("SUCCESS", good.status);
        assertEquals("VERIFY_REQUIRED", flaky.status);
    }

    @Test void authAndInternalRules() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments/" + UUID.randomUUID() + "/checkout")).andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/payments/" + UUID.randomUUID() + "/checkout").header("X-Internal-Token", TOKEN))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post("/internal/v1/orchestrator/attempts/x/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"trigger\":\"WEBHOOK\"}")).andExpect(status().isUnauthorized());
        verify("Anope", "MERCHANT").andExpect(status().isBadRequest());
        verify("Anope", "WEBHOOK").andExpect(status().isNotFound());
    }
}
