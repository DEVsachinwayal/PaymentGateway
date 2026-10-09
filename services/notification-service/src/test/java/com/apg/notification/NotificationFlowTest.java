package com.apg.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.apg.notification.service.NotificationProperties;
import com.apg.notification.service.NotificationService;
import com.apg.notification.service.WebhookSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {"POSTGRES_USER=ignored", "POSTGRES_PASSWORD=ignored", "INTERNAL_SERVICE_TOKEN=internal-token-for-tests-123",
        "apg.notification.allow-insecure-urls=true", "apg.notification.worker.enabled=false", "apg.notification.max-attempts=3",
        "apg.notification.request-timeout=1s"})
@AutoConfigureMockMvc
class NotificationFlowTest {
    static final String TOKEN = "internal-token-for-tests-123";
    static final FakeWorld world = new FakeWorld();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("apg.notification.merchant-service-uri", world::directoryUrl);
    }

    @AfterAll
    static void stop() { world.close(); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationService service;
    @Autowired ObjectMapper mapper;
    @Autowired NotificationProperties props;

    private String uniq() { return UUID.randomUUID().toString().replace("-", "").substring(0, 10); }

    private String event(String type, UUID eventId, UUID merchant) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type + "\",\"version\":1,\"occurredAt\":\"2026-10-09T10:00:00Z\",\"correlationId\":\"c-1\","
                + "\"aggregateId\":\"" + UUID.randomUUID() + "\",\"merchantId\":\"" + merchant + "\",\"payload\":{\"paymentId\":\"p-1\",\"amount\":10000,\"currency\":\"INR\",\"status\":\"SUCCESS\"}}";
    }

    private String jobStatus(UUID eventId) { return jdbc.queryForObject("select status from notification_jobs where event_id = ?", String.class, eventId); }

    private void makeDue() { jdbc.update("update notification_jobs set next_attempt_at = ?, lock_until = null where status in ('PENDING','RETRYING')", Timestamp.from(Instant.now().minusSeconds(1))); }

    // ------------------------------------------------------------------ delivery

    @Test void eventIsDeliveredSignedAndMinimal() throws Exception {
        UUID m = UUID.randomUUID(), eventId = UUID.randomUUID();
        String path = "/hook-" + uniq(), secret = "whsec_" + uniq();
        world.subscribe(m, "payment.success", path, secret);

        assertEquals(1, service.onEvent(event("payment.success", eventId, m)));
        assertEquals("PENDING", jobStatus(eventId));
        assertEquals(1, service.deliverDue());
        assertEquals("DELIVERED", jobStatus(eventId));

        FakeWorld.Received r = world.received.stream().filter(x -> x.path().equals(path)).findFirst().orElseThrow();
        String sig = r.headers().get("x-apg-signature");
        String ts = sig.substring(sig.indexOf("t=") + 2, sig.indexOf(','));
        assertEquals("v1=" + WebhookSender.sign(secret, ts + "." + r.body()), sig.substring(sig.indexOf(',') + 1), "HMAC over timestamp.rawBody with the endpoint secret");
        assertTrue(Math.abs(System.currentTimeMillis() / 1000 - Long.parseLong(ts)) < 30);
        assertEquals(eventId.toString(), r.headers().get("x-apg-event-id"));
        assertEquals("payment.success", r.headers().get("x-apg-event-type"));
        assertEquals("application/json", r.headers().get("content-type"));
        JsonNode body = mapper.readTree(r.body());
        assertEquals(eventId.toString(), body.path("id").asText());
        assertEquals("payment.success", body.path("type").asText());
        assertEquals(10000, body.path("data").path("amount").asLong());
        assertFalse(r.body().contains(m.toString()) || r.body().contains("correlationId") || r.body().contains(secret), "no internal ids or secrets in the payload");
        assertEquals(1, jdbc.queryForObject("select count(*) from notification_attempts where job_id = (select id from notification_jobs where event_id = ?) and outcome = 'OK'", Integer.class, eventId));
    }

    @Test void redeliveredEventsNeverNotifyTwice() throws Exception {
        UUID m = UUID.randomUUID(), eventId = UUID.randomUUID();
        String path = "/hook-" + uniq();
        world.subscribe(m, "payment.failed", path, "whsec_x");
        String json = event("payment.failed", eventId, m);
        assertEquals(1, service.onEvent(json));
        assertEquals(0, service.onEvent(json), "same event again => no new job");
        service.deliverDue();
        assertEquals(0, service.onEvent(json));
        service.deliverDue();
        assertEquals(1, world.hitsFor(path));
        assertEquals(1, jdbc.queryForObject("select count(*) from notification_jobs where event_id = ?", Integer.class, eventId));
    }

    @Test void onlySubscribedMerchantFacingEventsCreateJobs() throws Exception {
        UUID m = UUID.randomUUID();
        world.subscribe(m, "payment.success", "/h-" + uniq(), "s");
        assertEquals(0, service.onEvent(event("refund.success", UUID.randomUUID(), m)), "not subscribed to this event");
        assertEquals(0, service.onEvent(event("payment.created", UUID.randomUUID(), m)), "not a merchant-facing event");
        assertEquals(0, service.onEvent(event("settlement.created", UUID.randomUUID(), m)), "platform level");
        assertEquals(0, service.onEvent(event("payment.success", UUID.randomUUID(), new UUID(0, 0))), "platform merchant id");
        assertEquals(0, service.onEvent(event("payment.success", UUID.randomUUID(), UUID.randomUUID())), "merchant without endpoints");
        assertThrows(IllegalArgumentException.class, () -> service.onEvent("{\"eventType\":\"payment.success\",\"eventId\":\"nope\",\"merchantId\":\"" + m + "\"}"));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class, () -> service.onEvent("not json"));
    }

    @Test void merchantDirectoryOutageIsRetriedNeverTreatedAsNoSubscribers() throws Exception {
        UUID m = UUID.randomUUID(), eventId = UUID.randomUUID();
        String path = "/hook-" + uniq();
        world.subscribe(m, "payment.success", path, "s");
        world.directoryDown = true;
        try {
            assertThrows(RuntimeException.class, () -> service.onEvent(event("payment.success", eventId, m)));
        } finally {
            world.directoryDown = false;
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from notification_jobs where event_id = ?", Integer.class, eventId));
        assertEquals(1, service.onEvent(event("payment.success", eventId, m)), "the retried event is handled normally");

        // outage at delivery time: the job stays RETRYING (not cancelled)
        world.directoryDown = true;
        try {
            makeDue();
            service.deliverDue();
        } finally {
            world.directoryDown = false;
        }
        assertEquals("RETRYING", jobStatus(eventId));
        assertEquals(0, world.hitsFor(path));
    }

    // ------------------------------------------------------------------ retries

    @Test void failuresRetryWithBackoffThenGoDeadAndCanBeResent() throws Exception {
        UUID m = UUID.randomUUID(), eventId = UUID.randomUUID();
        String path = "/flaky-" + uniq();
        world.subscribe(m, "refund.failed", path, "s");
        world.answers.put(path, 500);
        service.onEvent(event("refund.failed", eventId, m));

        service.deliverDue();
        assertEquals("RETRYING", jobStatus(eventId));
        assertEquals(1, jdbc.queryForObject("select attempts from notification_jobs where event_id = ?", Integer.class, eventId));
        assertEquals(500, jdbc.queryForObject("select last_http_status from notification_jobs where event_id = ?", Integer.class, eventId));
        assertTrue(jdbc.queryForObject("select next_attempt_at > now() from notification_jobs where event_id = ?", Boolean.class, eventId), "backed off");
        assertEquals(0, service.deliverDue(), "not due yet");

        makeDue(); service.deliverDue();                 // attempt 2 fails
        makeDue(); service.deliverDue();                 // attempt 3 fails => DEAD (max-attempts=3)
        assertEquals("DEAD", jobStatus(eventId));
        assertEquals(3, world.hitsFor(path));
        makeDue();
        assertEquals(0, service.deliverDue(), "dead jobs are not attempted again");

        // the merchant fixed their server and resends from the dashboard
        world.answers.put(path, 200);
        UUID jobId = jdbc.queryForObject("select id from notification_jobs where event_id = ?", UUID.class, eventId);
        mvc.perform(user(MockMvcRequestBuilders.post("/api/v1/webhook-deliveries/" + jobId + "/resend"), m, "MERCHANT_ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETRYING")).andExpect(jsonPath("$.data.attempts").value(0));
        service.deliverDue();
        assertEquals("DELIVERED", jobStatus(eventId));
        mvc.perform(user(MockMvcRequestBuilders.get("/api/v1/webhook-deliveries/" + jobId), m, "MERCHANT_DEVELOPER")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.length()").value(4)).andExpect(jsonPath("$.data.history[0].outcome").value("HTTP_ERROR"))
                .andExpect(jsonPath("$.data.history[3].outcome").value("OK"));
    }

    @Test void timeoutsAndRedirectsAreFailuresAndRedirectsAreNotFollowed() throws Exception {
        UUID m = UUID.randomUUID(), slow = UUID.randomUUID(), redirect = UUID.randomUUID();
        String slowPath = "/slow-" + uniq(), redirPath = "/redir-" + uniq();
        world.subscribe(m, "payment.success", slowPath, "s");
        world.subscribe(m, "payment.failed", redirPath, "s");
        world.delayMillis.put(slowPath, 2500);
        world.answers.put(redirPath, 302);
        service.onEvent(event("payment.success", slow, m));
        service.onEvent(event("payment.failed", redirect, m));
        service.deliverDue();
        assertEquals("TIMEOUT", jdbc.queryForObject("select outcome from notification_attempts where job_id = (select id from notification_jobs where event_id = ?)", String.class, slow));
        assertEquals("HTTP_ERROR", jdbc.queryForObject("select outcome from notification_attempts where job_id = (select id from notification_jobs where event_id = ?)", String.class, redirect));
        assertEquals(1, world.hitsFor(redirPath));
        assertTrue(jdbc.queryForObject("select last_error from notification_jobs where event_id = ?", String.class, redirect).contains("redirects are not followed"));
    }

    @Test void deletedEndpointCancelsTheJob() throws Exception {
        UUID m = UUID.randomUUID(), eventId = UUID.randomUUID();
        FakeWorld.Sub sub = world.subscribe(m, "payment.success", "/gone-" + uniq(), "s");
        service.onEvent(event("payment.success", eventId, m));
        world.subscribers.get(m).get("payment.success").remove(sub);     // merchant deleted the endpoint meanwhile
        service.deliverDue();
        assertEquals("CANCELLED", jobStatus(eventId));
        assertEquals(0, world.hitsFor(sub.path()));
    }

    @Test void parallelWorkersNeverDeliverTheSameJobTwice() throws Exception {
        UUID m = UUID.randomUUID();
        String path = "/par-" + uniq();
        world.subscribe(m, "payment.success", path, "s");
        for (int i = 0; i < 6; i++) service.onEvent(event("payment.success", UUID.randomUUID(), m));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) fs.add(pool.submit(() -> service.deliverDue()));
        int total = 0;
        for (Future<Integer> f : fs) total += f.get();
        pool.shutdown();
        assertEquals(6, total);
        assertEquals(6, world.hitsFor(path));
    }

    // ------------------------------------------------------------------ SSRF guard

    @Test void sensitiveTargetsAreBlockedWhenInsecureUrlsAreOff() {
        WebhookSender strict = new WebhookSender(new NotificationProperties("x", false, 8, Duration.ofSeconds(10), Duration.ofHours(1), Duration.ofSeconds(5),
                new NotificationProperties.Worker(false, Duration.ofSeconds(2), 20)));
        String body = "{}";
        for (String url : new String[]{"http://example.com/h", "https://127.0.0.1/h", "https://localhost/h", "https://[::1]/h", "https://10.1.2.3/h",
                "https://192.168.1.10/h", "https://169.254.169.254/latest", "https://100.64.0.1/h", "https://0.0.0.0/h", "https://[fd00::1]/h",
                "https://user:pw@8.8.8.8/h", "ftp://8.8.8.8/h"}) {
            WebhookSender.Result r = strict.send(url, "e", "payment.success", body, "s");
            assertEquals("BLOCKED", r.outcome(), url);
            assertNull(r.httpStatus());
        }
        assertNull(strict.guard(URI.create("https://8.8.8.8/hook")), "a public address is acceptable");
    }

    @Test void blockedTargetsGoStraightToDeadBecauseWaitingWillNotHelp() throws Exception {
        // the flow-test sender is lenient, so make the job's URL point to something the strict rules would refuse
        // by checking the state machine directly: BLOCKED => DEAD on the first attempt
        UUID m = UUID.randomUUID(), eventId = UUID.randomUUID();
        FakeWorld.Sub sub = world.subscribe(m, "payment.success", "/x-" + uniq(), "s");
        service.onEvent(event("payment.success", eventId, m));
        jdbc.update("update notification_jobs set endpoint_url = 'ftp://127.0.0.1/h' where event_id = ?", eventId);
        service.deliverDue();
        assertEquals("DEAD", jobStatus(eventId));
        assertEquals(1, jdbc.queryForObject("select attempts from notification_jobs where event_id = ?", Integer.class, eventId));
        assertEquals(0, world.hitsFor(sub.path()));
    }

    // ------------------------------------------------------------------ merchant API

    private MockHttpServletRequestBuilder user(MockHttpServletRequestBuilder b, UUID merchant, String roles) {
        return b.header("X-Internal-Token", TOKEN).header("X-Auth-Type", "USER").header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Roles", roles).header("X-Merchant-Id", merchant.toString()).contentType(MediaType.APPLICATION_JSON);
    }

    @Test void merchantsSeeAndResendOnlyTheirOwnDeliveries() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        world.subscribe(a, "payment.success", "/a-" + uniq(), "s");
        UUID e1 = UUID.randomUUID();
        service.onEvent(event("payment.success", e1, a));
        service.deliverDue();
        UUID job = jdbc.queryForObject("select id from notification_jobs where event_id = ?", UUID.class, e1);

        mvc.perform(user(MockMvcRequestBuilders.get("/api/v1/webhook-deliveries"), a, "MERCHANT_ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.content[0].status").value("DELIVERED"));
        mvc.perform(user(MockMvcRequestBuilders.get("/api/v1/webhook-deliveries?status=DEAD"), a, "MERCHANT_ADMIN")).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(user(MockMvcRequestBuilders.get("/api/v1/webhook-deliveries?status=BANANA"), a, "MERCHANT_ADMIN")).andExpect(status().isBadRequest());
        mvc.perform(user(MockMvcRequestBuilders.get("/api/v1/webhook-deliveries"), b, "MERCHANT_ADMIN")).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(user(MockMvcRequestBuilders.get("/api/v1/webhook-deliveries/" + job), b, "MERCHANT_ADMIN")).andExpect(status().isNotFound());
        mvc.perform(user(MockMvcRequestBuilders.post("/api/v1/webhook-deliveries/" + job + "/resend"), b, "MERCHANT_ADMIN")).andExpect(status().isNotFound());
        mvc.perform(user(MockMvcRequestBuilders.post("/api/v1/webhook-deliveries/" + job + "/resend"), a, "MERCHANT_DEVELOPER")).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/webhook-deliveries").header("X-Internal-Token", TOKEN).header("X-Auth-Type", "API_KEY")
                .header("X-Merchant-Id", a.toString())).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/webhook-deliveries")).andExpect(status().isUnauthorized());

        // an in-flight delivery cannot be resent
        UUID e2 = UUID.randomUUID();
        service.onEvent(event("payment.success", e2, a));
        UUID pending = jdbc.queryForObject("select id from notification_jobs where event_id = ?", UUID.class, e2);
        mvc.perform(user(MockMvcRequestBuilders.post("/api/v1/webhook-deliveries/" + pending + "/resend"), a, "MERCHANT_ADMIN"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DELIVERY_IN_PROGRESS"));
        // the delivered payload and secrets are not part of the listing
        String list = mvc.perform(user(MockMvcRequestBuilders.get("/api/v1/webhook-deliveries"), a, "MERCHANT_ADMIN")).andReturn().getResponse().getContentAsString();
        assertFalse(list.contains("signingSecret") || list.contains("\"payload\""), list);
    }
}
