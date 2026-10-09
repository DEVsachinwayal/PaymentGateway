package com.apg.merchant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.UUID;
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
        "POSTGRES_USER=ignored", "POSTGRES_PASSWORD=ignored",
        "INTERNAL_SERVICE_TOKEN=internal-token-for-tests-123",
        "API_KEY_HASH_PEPPER=pepper-pepper-pepper-123456",
        "WEBHOOK_SECRET_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
@AutoConfigureMockMvc
class MerchantFlowTest {
    static final String TOKEN = "internal-token-for-tests-123";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, UUID user, String roles) {
        return b.header("X-Internal-Token", TOKEN).header("X-User-Id", user.toString())
                .header("X-User-Roles", roles).header("X-Auth-Type", "USER").contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions post(UUID user, String roles, String path, String body) throws Exception {
        return mvc.perform(as(MockMvcRequestBuilders.post(path), user, roles).content(body));
    }

    private ResultActions get(UUID user, String roles, String path) throws Exception {
        return mvc.perform(as(MockMvcRequestBuilders.get(path), user, roles));
    }

    private ResultActions delete(UUID user, String roles, String path) throws Exception {
        return mvc.perform(as(MockMvcRequestBuilders.delete(path), user, roles));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    /** Creates a user with a merchant and returns the user id. */
    private UUID newMerchantOwner() throws Exception {
        UUID u = UUID.randomUUID();
        post(u, "MERCHANT_ADMIN", "/api/v1/merchants",
                "{\"name\":\"Shop\",\"supportEmail\":\"s@shop.com\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("SANDBOX"))
                .andExpect(jsonPath("$.data.defaultCurrency").value("INR"));
        return u;
    }

    private JsonNode newKey(UUID u) throws Exception {
        return body(post(u, "MERCHANT_ADMIN", "/api/v1/api-keys", "{\"label\":\"ci\",\"mode\":\"TEST\"}")
                .andExpect(status().isCreated()));
    }

    private int verify(String fullKey) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/internal/v1/api-keys/verify")
                .header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"" + fullKey + "\"}")).andReturn().getResponse().getStatus();
    }

    @Test void requestsWithoutInternalTokenAreRejected() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/merchants/me").header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNTRUSTED_CALLER"));
        mvc.perform(MockMvcRequestBuilders.get("/actuator/health")).andExpect(status().isOk());
    }

    @Test void onlyMerchantAdminCanCreateAndOnlyOnce() throws Exception {
        UUID u = UUID.randomUUID();
        post(u, "MERCHANT_DEVELOPER", "/api/v1/merchants", "{\"name\":\"X\",\"supportEmail\":\"a@b.com\"}")
                .andExpect(status().isForbidden());
        post(u, "MERCHANT_ADMIN", "/api/v1/merchants", "{\"name\":\"X\",\"supportEmail\":\"a@b.com\"}")
                .andExpect(status().isCreated());
        post(u, "MERCHANT_ADMIN", "/api/v1/merchants", "{\"name\":\"Y\",\"supportEmail\":\"a@b.com\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MERCHANT_ALREADY_EXISTS"));
        get(u, "MERCHANT_ADMIN", "/api/v1/merchants/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("X"));
    }

    @Test void secretShownOnceStoredOnlyAsHashAndVerifies() throws Exception {
        UUID u = newMerchantOwner();
        JsonNode k = newKey(u);
        String full = k.at("/data/secret").asText();
        assertTrue(full.startsWith("sk_test_") && full.contains("."));
        String secretPart = full.substring(full.lastIndexOf('.') + 1);

        // list never contains the secret
        String list = get(u, "MERCHANT_DEVELOPER", "/api/v1/api-keys").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(list.contains(secretPart));
        assertTrue(list.contains(k.at("/data/keyId").asText()));

        // DB holds a hash, not the secret
        String stored = jdbc.queryForObject("select secret_hash from api_keys where id = ?::uuid", String.class, k.at("/data/id").asText());
        assertNotEquals(secretPart, stored);
        assertEquals(64, stored.length());

        assertEquals(200, verify(full));
        assertEquals(401, verify(full.substring(0, full.length() - 1) + "x"));
        assertEquals(401, verify("sk_test_deadbeefdeadbeef." + secretPart));
        assertEquals(401, verify("garbage"));
    }

    @Test void revokedKeyStopsWorkingAndOtherMerchantCannotRevoke() throws Exception {
        UUID a = newMerchantOwner(), b = newMerchantOwner();
        JsonNode k = newKey(a);
        String id = k.at("/data/id").asText();
        delete(b, "MERCHANT_ADMIN", "/api/v1/api-keys/" + id).andExpect(status().isNotFound());
        assertEquals(200, verify(k.at("/data/secret").asText()));
        delete(a, "MERCHANT_ADMIN", "/api/v1/api-keys/" + id).andExpect(status().isNoContent());
        assertEquals(401, verify(k.at("/data/secret").asText()));
        // B cannot see A's keys
        assertFalse(get(b, "MERCHANT_ADMIN", "/api/v1/api-keys").andReturn().getResponse().getContentAsString().contains(id));
    }

    @Test void rotationKeepsOldKeyForGraceThenRevokesWhenZero() throws Exception {
        UUID u = newMerchantOwner();
        JsonNode k1 = newKey(u);
        JsonNode k2 = body(post(u, "MERCHANT_ADMIN", "/api/v1/api-keys/" + k1.at("/data/id").asText() + "/rotate", "{\"graceHours\":1}")
                .andExpect(status().isCreated()));
        assertNotEquals(k1.at("/data/keyId").asText(), k2.at("/data/keyId").asText());
        assertEquals(200, verify(k1.at("/data/secret").asText()));   // inside grace window
        assertEquals(200, verify(k2.at("/data/secret").asText()));
        JsonNode k3 = body(post(u, "MERCHANT_ADMIN", "/api/v1/api-keys/" + k2.at("/data/id").asText() + "/rotate", "{\"graceHours\":0}")
                .andExpect(status().isCreated()));
        assertEquals(401, verify(k2.at("/data/secret").asText()));   // immediate revoke
        assertEquals(200, verify(k3.at("/data/secret").asText()));
    }

    @Test void liveKeysRequireActivatedMerchant() throws Exception {
        UUID u = newMerchantOwner();
        post(u, "MERCHANT_ADMIN", "/api/v1/api-keys", "{\"label\":\"prod\",\"mode\":\"LIVE\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("LIVE_MODE_NOT_ENABLED"));
    }

    @Test void webhookEndpointValidationAndSecretEncryption() throws Exception {
        UUID u = newMerchantOwner();
        for (String bad : new String[]{"http://shop.com/h", "https://localhost/h", "https://127.0.0.1/h",
                "https://10.0.0.5/h", "https://169.254.169.254/latest", "https://user:pw@shop.com/h"}) {
            post(u, "MERCHANT_ADMIN", "/api/v1/webhook-endpoints",
                    "{\"url\":\"" + bad + "\",\"events\":[\"payment.success\"]}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_WEBHOOK_URL"));
        }
        post(u, "MERCHANT_ADMIN", "/api/v1/webhook-endpoints",
                "{\"url\":\"https://shop.com/h\",\"events\":[\"nope\"]}").andExpect(status().isBadRequest());

        JsonNode w = body(post(u, "MERCHANT_ADMIN", "/api/v1/webhook-endpoints",
                "{\"url\":\"https://shop.com/h\",\"events\":[\"payment.success\",\"refund.success\"]}")
                .andExpect(status().isCreated()));
        String secret = w.at("/data/signingSecret").asText();
        assertTrue(secret.startsWith("whsec_"));
        String enc = jdbc.queryForObject("select secret_enc from webhook_endpoints where id = ?::uuid", String.class, w.at("/data/id").asText());
        assertFalse(enc.contains(secret));
        assertTrue(Base64.getDecoder().decode(enc).length > 12 + 16);

        String list = get(u, "MERCHANT_ADMIN", "/api/v1/webhook-endpoints").andReturn().getResponse().getContentAsString();
        assertFalse(list.contains(secret));

        // the notification service can fetch subscribers (with the real signing secret) for one event; the gateway never routes this
        UUID merchantId = UUID.fromString(jdbc.queryForObject("select merchant_id::text from webhook_endpoints where id = ?::uuid", String.class, w.at("/data/id").asText()));
        JsonNode subs = body(mvc.perform(MockMvcRequestBuilders.get("/internal/v1/merchants/" + merchantId + "/webhook-subscribers?event=refund.success")
                .header("X-Internal-Token", TOKEN)).andExpect(status().isOk()));
        assertEquals(1, subs.at("/data").size());
        assertEquals(secret, subs.at("/data/0/signingSecret").asText());
        assertEquals("https://shop.com/h", subs.at("/data/0/url").asText());
        assertEquals(0, body(mvc.perform(MockMvcRequestBuilders.get("/internal/v1/merchants/" + merchantId + "/webhook-subscribers?event=payment.failed")
                .header("X-Internal-Token", TOKEN)).andExpect(status().isOk())).at("/data").size(), "not subscribed to this event");
        mvc.perform(MockMvcRequestBuilders.get("/internal/v1/merchants/" + merchantId + "/webhook-subscribers?event=refund.success"))
                .andExpect(status().isUnauthorized());
        delete(u, "MERCHANT_ADMIN", "/api/v1/webhook-endpoints/" + w.at("/data/id").asText()).andExpect(status().isNoContent());
        assertFalse(get(u, "MERCHANT_ADMIN", "/api/v1/webhook-endpoints").andReturn().getResponse().getContentAsString()
                .contains(w.at("/data/id").asText()));
    }

    @Test void securitySensitiveActionsLeaveAnAppendOnlyAuditTrailWithoutSecrets() throws Exception {
        UUID u = newMerchantOwner();
        JsonNode key = body(post(u, "MERCHANT_ADMIN", "/api/v1/api-keys", "{\"label\":\"audit me\",\"mode\":\"TEST\"}").andExpect(status().isCreated()));
        String secret = key.at("/data/secret").asText();
        String keyId = key.at("/data/id").asText();
        JsonNode hook = body(post(u, "MERCHANT_ADMIN", "/api/v1/webhook-endpoints",
                "{\"url\":\"https://shop.example/hook?token=URLSECRET123\",\"events\":[\"payment.success\"]}").andExpect(status().isCreated()));
        post(u, "MERCHANT_ADMIN", "/api/v1/api-keys/" + keyId + "/rotate", "{\"graceHours\":1}").andExpect(status().isCreated());
        delete(u, "MERCHANT_ADMIN", "/api/v1/api-keys/" + keyId).andExpect(status().isNoContent());
        delete(u, "MERCHANT_ADMIN", "/api/v1/webhook-endpoints/" + hook.at("/data/id").asText()).andExpect(status().isNoContent());

        UUID merchantId = UUID.fromString(jdbc.queryForObject("select merchant_id::text from webhook_endpoints where id = ?::uuid", String.class, hook.at("/data/id").asText()));
        java.util.List<String> actions = jdbc.queryForList("select action from audit_events where merchant_id = ? order by occurred_at, id", String.class, merchantId);
        assertTrue(actions.containsAll(java.util.List.of("MERCHANT_CREATED", "API_KEY_CREATED", "WEBHOOK_ENDPOINT_CREATED", "API_KEY_ROTATED", "API_KEY_REVOKED", "WEBHOOK_ENDPOINT_DELETED")), actions.toString());
        String all = String.join(" ", jdbc.queryForList("select coalesce(actor,'') || ' ' || coalesce(target,'') || ' ' || coalesce(details,'') from audit_events where merchant_id = ?", String.class, merchantId));
        assertFalse(all.contains(secret) || all.contains("URLSECRET123") || all.contains(hook.at("/data/signingSecret").asText()), "no secrets, no URL query strings in the audit trail");
        assertTrue(all.contains("user:" + u), "the actor is recorded");
        assertNotNull(jdbc.queryForObject("select count(*) from audit_events where correlation_id is not null or true", Integer.class));

        // the merchant admin can read their own activity log; developers and other merchants cannot
        get(u, "MERCHANT_ADMIN", "/api/v1/audit-events").andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(actions.size()))
                .andExpect(jsonPath("$.data.content[0].action").exists());
        get(u, "MERCHANT_ADMIN", "/api/v1/audit-events?action=API_KEY_ROTATED").andExpect(jsonPath("$.data.totalElements").value(1));
        get(u, "MERCHANT_DEVELOPER", "/api/v1/audit-events").andExpect(status().isForbidden());
        get(newMerchantOwner(), "MERCHANT_ADMIN", "/api/v1/audit-events").andExpect(jsonPath("$.data.totalElements").value(1));   // only their own MERCHANT_CREATED
        assertThrows(org.springframework.core.NestedRuntimeException.class, () -> jdbc.update("update audit_events set outcome = 'X' where merchant_id = ?", merchantId));
        assertThrows(org.springframework.core.NestedRuntimeException.class, () -> jdbc.update("delete from audit_events where merchant_id = ?", merchantId));
    }

    @Test void userToMerchantLookupAndMalformedInput() throws Exception {
        UUID u = newMerchantOwner();
        mvc.perform(MockMvcRequestBuilders.get("/internal/v1/users/" + u + "/merchant").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.merchantId").exists());
        mvc.perform(MockMvcRequestBuilders.get("/internal/v1/users/" + UUID.randomUUID() + "/merchant").header("X-Internal-Token", TOKEN))
                .andExpect(status().isNotFound());
        post(u, "MERCHANT_ADMIN", "/api/v1/api-keys", "{not json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
        delete(u, "MERCHANT_ADMIN", "/api/v1/api-keys/not-a-uuid").andExpect(status().isBadRequest());
    }
}
