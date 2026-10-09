package com.apg.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayFlowTest.ExtraRoutes.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayFlowTest {
    static final String SECRET = "gateway-test-secret-gateway-test-secret-123";
    static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /** Stub downstream: echoes selected request headers so tests can see what the gateway forwarded. */
    static final HttpServer backend = startBackend();

    static HttpServer startBackend() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                int code = 200;
                String body;
                if (path.equals("/internal/v1/api-keys/verify")) {
                    String in = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (in.contains("sk_test_good.secret")) {
                        body = "{\"data\":{\"merchantId\":\"merch-api\",\"apiKeyId\":\"key-1\",\"mode\":\"TEST\"}}";
                    } else {
                        code = 401;
                        body = "{\"error\":{\"code\":\"INVALID_API_KEY\"}}";
                    }
                } else if (path.startsWith("/internal/v1/users/")) {
                    if (path.contains("user-123")) {
                        body = "{\"data\":{\"merchantId\":\"merch-user\"}}";
                    } else {
                        code = 404;
                        body = "{\"error\":{\"code\":\"NO_MERCHANT\"}}";
                    }
                } else {
                    body = "{\"path\":\"" + path + "\",\"userId\":\""
                            + nz(ex.getRequestHeaders().getFirst("X-User-Id")) + "\",\"roles\":\""
                            + nz(ex.getRequestHeaders().getFirst("X-User-Roles")) + "\",\"merchant\":\""
                            + nz(ex.getRequestHeaders().getFirst("X-Merchant-Id")) + "\",\"authType\":\""
                            + nz(ex.getRequestHeaders().getFirst("X-Auth-Type")) + "\",\"internal\":\""
                            + nz(ex.getRequestHeaders().getFirst("X-Internal-Token")) + "\",\"corr\":\""
                            + nz(ex.getRequestHeaders().getFirst("X-Correlation-Id")) + "\"}";
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

    static String nz(String s) { return s == null ? "" : s; }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String url = "http://127.0.0.1:" + backend.getAddress().getPort();
        r.add("apg.gateway.jwt-secret", () -> SECRET);
        r.add("apg.gateway.auth-uri", () -> url);
        r.add("apg.gateway.merchant-uri", () -> url);
        r.add("apg.gateway.order-uri", () -> url);
        r.add("apg.gateway.payment-uri", () -> url);
        r.add("apg.gateway.orchestrator-uri", () -> url);
        r.add("apg.gateway.webhook-uri", () -> url);
        r.add("apg.gateway.refund-uri", () -> url);
        r.add("apg.gateway.internal-token", () -> "internal-token-for-tests-123");
        r.add("apg.gateway.rate-limit.replenish-per-second", () -> 1);
        r.add("apg.gateway.rate-limit.burst", () -> 3);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("test.backend.url", () -> url);
    }

    @AfterAll
    static void stop() { backend.stop(0); }

    /** A protected route that does not exist in production yet. */
    @TestConfiguration
    static class ExtraRoutes {
        @Bean
        RouteLocator protectedTestRoute(RouteLocatorBuilder b, org.springframework.core.env.Environment env) {
            return b.routes()
                    .route("protected-test", r -> r.path("/api/v1/protected-test/**").uri(env.getProperty("test.backend.url")))
                    .build();
        }
    }

    @Autowired WebTestClient web;

    private static String token(String sub, List<String> roles, Instant exp, SecretKey key) {
        return Jwts.builder().issuer("apg-auth").subject(sub).claim("email", "a@b.com").claim("roles", roles)
                .issuedAt(Date.from(Instant.now().minusSeconds(60))).expiration(Date.from(exp)).signWith(key).compact();
    }

    private String valid() {
        return token("user-123", List.of("MERCHANT_ADMIN"), Instant.now().plusSeconds(300), KEY);
    }

    @Test @Order(1)
    void protectedRouteRequiresValidToken() {
        web.get().uri("/api/v1/protected-test/x").exchange()
                .expectStatus().isUnauthorized().expectBody().jsonPath("$.error.code").isEqualTo("MISSING_TOKEN");
        web.get().uri("/api/v1/protected-test/x").header("Authorization", "Bearer garbage").exchange()
                .expectStatus().isUnauthorized().expectBody().jsonPath("$.error.code").isEqualTo("INVALID_TOKEN");
        web.get().uri("/api/v1/protected-test/x")
                .header("Authorization", "Bearer " + token("u", List.of(), Instant.now().minusSeconds(10), KEY))
                .exchange().expectStatus().isUnauthorized();
        SecretKey other = Keys.hmacShaKeyFor("a-completely-different-secret-value-0000".getBytes(StandardCharsets.UTF_8));
        web.get().uri("/api/v1/protected-test/x")
                .header("Authorization", "Bearer " + token("u", List.of(), Instant.now().plusSeconds(60), other))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test @Order(2)
    void validTokenForwardsIdentityAndSpoofedHeadersAreOverwritten() {
        web.get().uri("/api/v1/protected-test/x")
                .header("Authorization", "Bearer " + valid())
                .header("X-User-Id", "attacker").header("X-User-Roles", "SYSTEM_ADMIN")
                .exchange().expectStatus().isOk()
                .expectHeader().exists("X-Correlation-Id")
                .expectBody()
                .jsonPath("$.userId").isEqualTo("user-123")
                .jsonPath("$.roles").isEqualTo("MERCHANT_ADMIN");
    }

    @Test @Order(2)
    void userRequestsCarryMerchantAndInternalTokenAndSpoofedMerchantIsIgnored() {
        web.get().uri("/api/v1/protected-test/x")
                .header("Authorization", "Bearer " + valid())
                .header("X-Merchant-Id", "attacker-merchant").header("X-Auth-Type", "API_KEY")
                .header("X-Internal-Token", "guess")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.merchant").isEqualTo("merch-user")
                .jsonPath("$.authType").isEqualTo("USER")
                .jsonPath("$.internal").isEqualTo("internal-token-for-tests-123");
        // user without a merchant: no X-Merchant-Id at all
        web.get().uri("/api/v1/protected-test/x")
                .header("Authorization", "Bearer " + token("user-no-merchant", List.of("MERCHANT_ADMIN"), Instant.now().plusSeconds(60), KEY))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.merchant").isEqualTo("");
    }

    @Test @Order(2)
    void apiKeyAuthenticationForMerchantApiPathsOnly() {
        web.get().uri("/api/v1/orders/abc").header("Authorization", "Bearer sk_test_good.secret")
                .header("X-Merchant-Id", "attacker").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.merchant").isEqualTo("merch-api").jsonPath("$.authType").isEqualTo("API_KEY")
                .jsonPath("$.userId").isEqualTo("");
        web.get().uri("/api/v1/protected-test/x").header("X-Api-Key", "sk_test_good.secret")
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.error.code").isEqualTo("FORBIDDEN_FOR_API_KEY");
        web.get().uri("/api/v1/merchants/me").header("X-Api-Key", "sk_test_good.secret")
                .exchange().expectStatus().isForbidden();
        web.get().uri("/api/v1/orders/abc").header("X-Api-Key", "sk_test_bad.secret")
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.error.code").isEqualTo("INVALID_API_KEY");
    }

    @Test @Order(2)
    void refundsAreRoutedAndAcceptApiKeysButNeverAnonymous() {
        web.get().uri("/api/v1/refunds/abc").exchange().expectStatus().isUnauthorized();
        web.get().uri("/api/v1/refunds/abc").header("Authorization", "Bearer sk_test_good.secret")
                .header("X-Merchant-Id", "attacker").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.path").isEqualTo("/api/v1/refunds/abc").jsonPath("$.merchant").isEqualTo("merch-api");
    }

    @Test @Order(2)
    void checkoutPageIsPublicButCheckoutApiIsNot() {
        web.get().uri("/checkout/some-token").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.path").isEqualTo("/checkout/some-token").jsonPath("$.internal").isEqualTo("internal-token-for-tests-123");
        web.post().uri("/api/v1/payments/abc/checkout").exchange().expectStatus().isUnauthorized();
        web.post().uri("/api/v1/payments/abc/checkout").header("X-Api-Key", "sk_test_good.secret").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.merchant").isEqualTo("merch-api");
    }

    @Test @Order(2)
    void providerCallbacksAndResultPageArePublic() {
        web.post().uri("/api/v1/webhooks/payu").header("X-User-Id", "attacker").bodyValue("txnid=A1")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.path").isEqualTo("/api/v1/webhooks/payu").jsonPath("$.userId").isEqualTo("")
                .jsonPath("$.internal").isEqualTo("internal-token-for-tests-123");
        web.get().uri("/payment-result?status=SUCCESS").exchange().expectStatus().isOk();
        web.get().uri("/api/v1/webhooks/payu").exchange().expectStatus().isUnauthorized();   // only POST is public
    }

    @Test @Order(3)
    void correlationIdIsPropagatedOrGenerated() {
        web.get().uri("/api/v1/protected-test/x").header("Authorization", "Bearer " + valid())
                .header("X-Correlation-Id", "my-corr-id-0001").exchange()
                .expectHeader().valueEquals("X-Correlation-Id", "my-corr-id-0001")
                .expectBody().jsonPath("$.corr").isEqualTo("my-corr-id-0001");
        web.get().uri("/api/v1/protected-test/x").header("Authorization", "Bearer " + valid())
                .header("X-Correlation-Id", "bad id with spaces!").exchange()
                .expectHeader().value("X-Correlation-Id", v -> assertTrue(v.startsWith("req_")));
    }

    @Test @Order(4)
    void spoofedIdentityHeadersNeverReachPublicRoutes() {
        web.post().uri("/api/v1/auth/login").header("X-User-Id", "attacker").bodyValue("{}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("/api/v1/auth/login").jsonPath("$.userId").isEqualTo("");
    }

    @Test @Order(5)
    void oversizedBodyRejected() {
        byte[] big = new byte[300_000];
        web.post().uri("/api/v1/auth/login").bodyValue(big).exchange().expectStatus().isEqualTo(413);
    }

    @Test @Order(9)
    void authRouteIsRateLimited() {
        int limited = 0;
        for (int i = 0; i < 12; i++) {
            var status = web.post().uri("/api/v1/auth/login").bodyValue("{}").exchange().returnResult(String.class)
                    .getStatus();
            if (status.value() == 429) limited++;
        }
        assertTrue(limited > 0, "expected at least one 429 after exceeding burst");
    }

    @Test @Order(10)
    void healthIsPublic() {
        web.get().uri("/actuator/health").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }
}
