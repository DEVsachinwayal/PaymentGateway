package com.apg.webhook;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.apg.webhook.service.WebhookService;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {"POSTGRES_USER=ignored", "POSTGRES_PASSWORD=ignored",
        "INTERNAL_SERVICE_TOKEN=internal-token-for-tests-123", "apg.webhook.retry.enabled=false"})
@AutoConfigureMockMvc
class WebhookFlowTest {
    static final String TOKEN = "internal-token-for-tests-123";
    static final FakePeers peers = new FakePeers();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("apg.webhook.provider-service-uri", peers::baseUrl);
        r.add("apg.webhook.orchestrator-uri", peers::baseUrl);
        r.add("apg.webhook.payment-service-uri", peers::baseUrl);
    }

    @AfterAll
    static void stop() { peers.close(); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebhookService service;

    private String ref() { return "A" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); }

    private String form(String ref, String hash, String status) {
        return "txnid=" + ref + "&mihpayid=403993715&status=" + status + "&unmappedstatus=captured&amount=499.00&hash=" + hash
                + "&key=testkey&firstname=Asha&email=asha%40example.com&phone=9876543210&cardnum=512345XXXXXX2346&name_on_card=Asha+K";
    }

    private ResultActions post(String ref, String hash, String status, String accept) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/payu").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(accept).content(form(ref, hash, status)));
    }

    private int calls(String ref) { return peers.verifyCalls.containsKey(ref) ? peers.verifyCalls.get(ref).get() : 0; }

    private String eventStatus(String ref) {
        return jdbc.queryForObject("select status from webhook_events where provider_reference = ? and authentic", String.class, ref);
    }

    @Test void authenticServerCallbackIsStoredSanitizedAndHandedToOrchestratorOnce() throws Exception {
        String ref = ref();
        post(ref, "valid-hash", "success", "application/json").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("received"));
        assertEquals(1, calls(ref));
        assertEquals("PROCESSED", eventStatus(ref));

        String payload = jdbc.queryForObject("select payload from webhook_events where provider_reference = ?", String.class, ref);
        assertTrue(payload.contains(ref) && payload.contains("403993715"));
        for (String secret : new String[]{"hash", "cardnum", "512345", "name_on_card", "email", "asha@example.com", "phone", "9876543210", "firstname", "valid-hash"}) {
            assertFalse(payload.contains(secret), "payload must not store " + secret);
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where event_type='webhook.received' and payload like ?", Integer.class, "%" + ref + "%"));
        assertEquals(1, jdbc.queryForObject("select count(*) from webhook_delivery_attempts a join webhook_events e on e.id=a.event_id where e.provider_reference=? and a.outcome='OK'", Integer.class, ref));
    }

    @Test void duplicateAndReplayedCallbacksAreAcknowledgedButNeverReprocessed() throws Exception {
        String ref = ref();
        post(ref, "valid-hash", "success", "application/json").andExpect(status().isOk());
        for (int i = 0; i < 3; i++) post(ref, "valid-hash", "success", "application/json").andExpect(status().isOk());
        assertEquals(1, calls(ref), "orchestrator must be asked exactly once");
        assertEquals(1, jdbc.queryForObject("select count(*) from webhook_events where provider_reference = ? and authentic", Integer.class, ref));
    }

    @Test void forgedCallbackIsRejectedAndNeverReachesTheOrchestrator() throws Exception {
        String ref = ref();
        post(ref, "forged", "success", "application/json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SIGNATURE"));
        assertEquals(0, calls(ref));
        assertEquals("REJECTED", jdbc.queryForObject("select status from webhook_events where provider_reference = ?", String.class, ref));
        assertNull(jdbc.queryForObject("select payload from webhook_events where provider_reference = ?", String.class, ref), "unauthenticated payload is not kept");

        post(ref, "forged", "success", "text/html").andExpect(status().isBadRequest());     // browser variant: neutral page, no details
    }

    @Test void browserReturnIsRedirectedBackToTheMerchantWithOnlyStatusAndPaymentId() throws Exception {
        String ref = ref();
        String location = post(ref, "valid-hash", "success", "text/html").andExpect(status().isSeeOther())
                .andExpect(header().string("Referrer-Policy", "no-referrer")).andReturn().getResponse().getHeader("Location");
        assertEquals("https://merchant.example/return?status=SUCCESS&paymentId=11111111-1111-1111-1111-111111111111", location);
        assertFalse(location.contains(ref) || location.contains("403993715") || location.contains("hash"));

        peers.returnUrl = null;
        try {
            String ref2 = ref();
            String loc2 = post(ref2, "valid-hash", "success", "text/html").andExpect(status().isSeeOther()).andReturn().getResponse().getHeader("Location");
            assertTrue(loc2.startsWith("/payment-result?status=SUCCESS"), loc2);
        } finally {
            peers.returnUrl = "https://merchant.example/return";
        }
    }

    @Test void orchestratorOutageIsAcknowledgedStoredAndRetriedUntilItWorks() throws Exception {
        String ref = ref();
        peers.orchestratorStatus.put(ref, 503);
        post(ref, "valid-hash", "success", "application/json").andExpect(status().isOk());     // provider must not keep hammering us
        assertEquals("RETRYING", eventStatus(ref));
        assertEquals(1, jdbc.queryForObject("select attempts from webhook_events where provider_reference = ?", Integer.class, ref));

        jdbc.update("update webhook_events set next_retry_at = now() - interval '1 second' where provider_reference = ?", ref);
        assertTrue(service.retryDue() >= 1);
        assertEquals("RETRYING", eventStatus(ref), "still down");

        peers.orchestratorStatus.remove(ref);
        jdbc.update("update webhook_events set next_retry_at = now() - interval '1 second' where provider_reference = ?", ref);
        service.retryDue();
        assertEquals("PROCESSED", eventStatus(ref));
        assertEquals(3, calls(ref));
        assertEquals(3, jdbc.queryForObject("select count(*) from webhook_delivery_attempts a join webhook_events e on e.id=a.event_id where e.provider_reference=?", Integer.class, ref));
    }

    @Test void eventBecomesDeadLetterAfterMaxAttempts() throws Exception {
        String ref = ref();
        peers.orchestratorStatus.put(ref, 503);
        post(ref, "valid-hash", "success", "application/json").andExpect(status().isOk());
        for (int i = 0; i < 12 && !"DEAD".equals(eventStatus(ref)); i++) {
            jdbc.update("update webhook_events set next_retry_at = now() - interval '1 second' where provider_reference = ? and status = 'RETRYING'", ref);
            service.retryDue();
        }
        assertEquals("DEAD", eventStatus(ref));
        assertEquals(8, jdbc.queryForObject("select attempts from webhook_events where provider_reference = ?", Integer.class, ref));
        int before = calls(ref);
        service.retryDue();
        assertEquals(before, calls(ref), "dead events are not retried automatically");
    }

    @Test void callbackForUnknownTransactionIsParkedNotRetried() throws Exception {
        String ref = ref();
        peers.orchestratorStatus.put(ref, 404);
        post(ref, "valid-hash", "success", "application/json").andExpect(status().isOk());
        assertEquals("UNMATCHED", eventStatus(ref));
        service.retryDue();
        assertEquals(1, calls(ref));
    }

    @Test void inputGuards() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/payu").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.TEXT_PLAIN).content("x")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/unknownprovider").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content(form(ref(), "x", "success"))).andExpect(status().isNotFound());
        StringBuilder big = new StringBuilder("txnid=A1&status=success&amount=1.00&hash=x&key=k&note=");
        big.append("z".repeat(2500));
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/payu").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content(big.toString())).andExpect(status().isPayloadTooLarge());
        StringBuilder many = new StringBuilder("txnid=A1");
        for (int i = 0; i < 150; i++) many.append("&f").append(i).append("=v");
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/payu").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content(many.toString())).andExpect(status().isPayloadTooLarge());

        peers.providerVerifierDown = true;
        try {
            mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/payu").header("X-Internal-Token", TOKEN)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).content(form(ref(), "valid-hash", "success")))
                    .andExpect(status().isServiceUnavailable());      // cannot authenticate => ask the provider to retry later
        } finally {
            peers.providerVerifierDown = false;
        }
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/payu").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(form(ref(), "valid-hash", "success"))).andExpect(status().isUnauthorized());   // internal token still required (gateway adds it)
    }

    @Test void resultPageShowsOnlyFixedMessages() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/payment-result").param("status", "SUCCESS").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Payment successful")));
        String evil = "<script>alert(1)</script>";
        String body = mvc.perform(MockMvcRequestBuilders.get("/payment-result").param("status", evil).header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("<script>alert"), "request text must never be reflected");
        assertTrue(body.contains("could not determine"));
    }
}
