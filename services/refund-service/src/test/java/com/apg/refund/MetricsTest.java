package com.apg.refund;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The business metrics the alerts are built on (docs/OBSERVABILITY.md) really appear on /actuator/prometheus. */
@Testcontainers
@SpringBootTest(properties = {"POSTGRES_USER=ignored", "POSTGRES_PASSWORD=ignored", "INTERNAL_SERVICE_TOKEN=internal-token-for-tests-123",
        "apg.refund.worker.enabled=false"})
@AutoConfigureMockMvc
@AutoConfigureObservability
class MetricsTest {
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

    @Test void refundMetricsAndBacklogGaugesAreExposed() throws Exception {
        UUID merchant = UUID.randomUUID();
        FakePeers.Payment p = peers.addPayment(merchant, 3000, "SUCCESS");
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/refunds").header("X-Internal-Token", "internal-token-for-tests-123")
                .header("X-Merchant-Id", merchant.toString()).header("X-Auth-Type", "API_KEY").header("Idempotency-Key", "m1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"" + p.id() + "\"}")).andExpect(status().isAccepted());

        String text = mvc.perform(MockMvcRequestBuilders.get("/actuator/prometheus")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(java.util.regex.Pattern.compile("apg_refund_total\\{[^}]*status=\"CREATED\"[^}]*\\}").matcher(text).find(), "refund counter");
        assertTrue(text.contains("application=\"refund-service\""), "every metric is tagged with the service name");
        assertTrue(text.contains("apg_refund_in_flight"), "in-flight gauge");
        assertTrue(text.contains("apg_refund_verify_required"), "verify-required gauge");
        assertTrue(text.contains("jvm_memory_used_bytes") && text.contains("http_server_requests_seconds"), "platform metrics");
        assertFalse(text.contains(merchant.toString()) || text.contains(p.id().toString()), "no ids or customer data in metric labels");
    }
}
