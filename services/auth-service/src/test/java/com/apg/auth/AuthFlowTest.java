package com.apg.auth;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.apg.auth.repo.Repositories.LoginAuditRepository;
import com.apg.auth.service.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "POSTGRES_USER=ignored", "POSTGRES_PASSWORD=ignored",
        "JWT_SECRET=test-secret-test-secret-test-secret-12345"})
@AutoConfigureMockMvc
class AuthFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired LoginAuditRepository audit;

    private static final String PW = "CorrectHorse-Battery1";

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String email() { return "u" + UUID.randomUUID().toString().substring(0, 8) + "@example.com"; }

    private void register(String email) throws Exception {
        postJson("/api/v1/auth/register", """
                {"email":"%s","password":"%s","fullName":"Test User"}""".formatted(email, PW))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value(email));
    }

    private JsonNode login(String email, String pw, int expectedStatus) throws Exception {
        String res = postJson("/api/v1/auth/login", """
                {"email":"%s","password":"%s"}""".formatted(email, pw))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    private ResultActions refresh(String token) throws Exception {
        return postJson("/api/v1/auth/refresh", """
                {"refreshToken":"%s"}""".formatted(token));
    }

    @Test void registerLoginAndJwtContents() throws Exception {
        String e = email();
        register(e);
        JsonNode r = login(e, PW, 200);
        String access = r.at("/data/accessToken").asText();
        assertNotNull(tokens.verifyAndGetSubject(access));
        assertEquals("Bearer", r.at("/data/tokenType").asText());
        assertTrue(r.at("/data/refreshToken").asText().length() >= 40);
        // access token carries role claim, never the password hash
        String payload = new String(java.util.Base64.getUrlDecoder().decode(access.split("\\.")[1]));
        assertTrue(payload.contains("MERCHANT_ADMIN"));
        assertFalse(payload.contains("hash"));
    }

    @Test void duplicateEmailAndWeakPasswordRejected() throws Exception {
        String e = email();
        register(e);
        postJson("/api/v1/auth/register", """
                {"email":"%s","password":"%s","fullName":"X"}""".formatted(e.toUpperCase(), PW))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"));
        postJson("/api/v1/auth/register", """
                {"email":"%s","password":"short","fullName":"X"}""".formatted(email()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test void wrongPasswordAndUnknownUserGiveSameError() throws Exception {
        String e = email();
        register(e);
        JsonNode a = login(e, "wrong-password-123", 401);
        JsonNode b = login(email(), "wrong-password-123", 401);
        assertEquals(a.at("/error/code").asText(), b.at("/error/code").asText());
        assertEquals("INVALID_CREDENTIALS", a.at("/error/code").asText());
    }

    @Test void accountLocksAfterFiveFailuresEvenForCorrectPassword() throws Exception {
        String e = email();
        register(e);
        for (int i = 0; i < 5; i++) login(e, "wrong-password-123", 401);
        JsonNode locked = login(e, PW, 423);
        assertEquals("ACCOUNT_LOCKED", locked.at("/error/code").asText());
        assertTrue(audit.findAll().stream().anyMatch(a -> "LOCKED".equals(a.getReason())));
    }

    @Test void refreshRotatesAndReuseRevokesWholeFamily() throws Exception {
        String e = email();
        register(e);
        String r1 = login(e, PW, 200).at("/data/refreshToken").asText();

        String body = refresh(r1).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String r2 = json.readTree(body).at("/data/refreshToken").asText();
        assertNotEquals(r1, r2);

        // replay of the already-rotated token => theft assumed
        refresh(r1).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_REUSED"));
        // the legitimate newest token is dead as well because the family was revoked
        refresh(r2).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_REUSED"));
    }

    @Test void logoutRevokesSession() throws Exception {
        String e = email();
        register(e);
        String r = login(e, PW, 200).at("/data/refreshToken").asText();
        postJson("/api/v1/auth/logout", """
                {"refreshToken":"%s"}""".formatted(r)).andExpect(status().isNoContent());
        refresh(r).andExpect(status().isUnauthorized());
    }

    @Test void garbageRefreshTokenRejected() throws Exception {
        refresh("not-a-real-token").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test void otherEndpointsAreDeniedAndHealthIsOpen() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/anything"))
                .andExpect(status().is(anyOf(is(401), is(403))));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
