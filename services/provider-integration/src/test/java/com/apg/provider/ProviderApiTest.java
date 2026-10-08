package com.apg.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.apg.provider.payu.FakePayU;
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
        "INTERNAL_SERVICE_TOKEN=internal-token-for-tests-123"})
@AutoConfigureMockMvc
class ProviderApiTest {
    static final String TOKEN = "internal-token-for-tests-123";
    static final FakePayU fake = new FakePayU();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("apg.provider.payu.merchant-key", () -> FakePayU.KEY);
        r.add("apg.provider.payu.merchant-salt", () -> FakePayU.SALT);
        r.add("apg.provider.payu.base-url", fake::baseUrl);
    }

    @AfterAll
    static void stop() { fake.close(); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private ResultActions get(String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).header("X-Internal-Token", TOKEN));
    }

    private ResultActions post(String path, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test void internalTokenIsRequired() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/internal/v1/providers")).andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.get("/internal/v1/providers").header("X-Internal-Token", "wrong-token-value-xx"))
                .andExpect(status().isUnauthorized());
    }

    @Test void providerIsRegisteredWithItsNonSecretConfig() throws Exception {
        get("/internal/v1/providers").andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].provider").value("payu"))
                .andExpect(jsonPath("$.data[0].enabled").value(true))
                .andExpect(jsonPath("$.data[0].priority").value(1))
                .andExpect(jsonPath("$.data[0].health.up").value(true));
        assertEquals(1, jdbc.queryForObject("select count(*) from provider_audit where action = 'CONFIG_LOADED'", Integer.class));
        get("/internal/v1/providers/unknown/health").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PROVIDER_NOT_FOUND"));
    }

    @Test void checkoutStatusAndCallbackEndpoints() throws Exception {
        String ref = "A" + Long.toHexString(System.nanoTime()) + "z";
        String body = """
                {"providerReference":"%s","amountMinor":49900,"currency":"INR","description":"Order 1","customerName":"Asha",
                 "customerEmail":"asha@example.com","paymentId":"pay-1","successUrl":"https://apg.example/cb","failureUrl":"https://apg.example/cb"}"""
                .formatted(ref);
        post("/internal/v1/providers/payu/checkout", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.method").value("POST"))
                .andExpect(jsonPath("$.data.fields.amount").value("499.00"))
                .andExpect(jsonPath("$.data.fields.hash").isString())
                .andExpect(jsonPath("$.data.fields.txnid").value(ref));

        // validation: bad reference, http (non-localhost) return urls, wrong currency
        post("/internal/v1/providers/payu/checkout", body.replace(ref, "short")).andExpect(status().isBadRequest());
        post("/internal/v1/providers/payu/checkout", body.replace("https://apg.example/cb", "http://evil.example/cb")).andExpect(status().isBadRequest());
        post("/internal/v1/providers/payu/checkout", body.replace("INR", "USD")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_CURRENCY"));

        fake.scenarios.put(ref, FakePayU.tx(ref, "success", "captured", "499.00"));
        get("/internal/v1/providers/payu/payments/" + ref + "/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS")).andExpect(jsonPath("$.data.amountMinor").value(49900));

        get("/internal/v1/providers/payu/payments/never-existed/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_FOUND"));

        fake.scenarios.put("T-HTML", "<html>down</html>");
        get("/internal/v1/providers/payu/payments/T-HTML/status").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PROVIDER_UNAVAILABLE"));

        post("/internal/v1/providers/payu/callbacks/verify", "{\"fields\":{\"txnid\":\"x\",\"status\":\"success\",\"amount\":\"1.00\",\"key\":\"testkey\",\"hash\":\"bad\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.authentic").value(false));
    }

    @Test void auditTablesNeverContainSecretsOrHashes() throws Exception {
        checkoutStatusAndCallbackEndpoints();
        Integer rows = jdbc.queryForObject("select count(*) from provider_attempts", Integer.class);
        assertTrue(rows != null && rows > 0, "provider calls must be logged");
        String dump = String.join("\n", jdbc.queryForList(
                "select concat_ws('|',provider,operation,reference,outcome,detail) from provider_attempts", String.class))
                + String.join("\n", jdbc.queryForList("select concat_ws('|',provider,actor,action,detail) from provider_audit", String.class))
                + String.join("\n", jdbc.queryForList("select concat_ws('|',provider,mode,base_url) from provider_configs", String.class));
        assertFalse(dump.contains(FakePayU.SALT), "salt leaked into audit tables");
        assertFalse(dump.matches("(?s).*[0-9a-f]{100,}.*"), "a hash leaked into audit tables");
    }
}
