package com.apg.common.logging;

import static org.junit.jupiter.api.Assertions.*;

import com.apg.common.web.CorrelationIdFilter;
import com.apg.common.web.OutboundHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

class LoggingTest {
    // ------------------------------------------------------------------ masking

    @Test void secretsInTextAreMasked() {
        String key = "sk_test_ab12cd34ef56.0123456789abcdef0123456789abcdef";
        String whsec = "whsec_Zm9vYmFyYmF6cXV4";
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLXNpZ25hdHVyZQ";
        String msg = LogMasker.mask("key=" + key + " hook " + whsec + " token " + jwt + " Authorization: Bearer abcdefghijklmnop1234");
        assertFalse(msg.contains("ab12cd34ef56") || msg.contains("Zm9vYmFy") || msg.contains("c2lnbmF0dXJl") || msg.contains("abcdefghijklmnop1234"), msg);
        assertTrue(msg.contains("***"));
    }

    @Test void secretLookingKeyValuePairsAreMasked() {
        for (String s : new String[]{"password=hunter2hunter2", "{\"password\":\"hunter2hunter2\"}", "salt: abcDEF123", "PAYU_MERCHANT_SALT=abcDEF123",
                "cvv=123", "hash=1f3c9aaabbcc", "secret=\"s3cr3t-value\"", "refreshToken=abc123xyz789"}) {
            String out = LogMasker.mask(s);
            assertFalse(out.contains("hunter2") || out.contains("abcDEF123") || out.contains("=123") || out.contains("1f3c9aaabbcc")
                    || out.contains("s3cr3t") || out.contains("abc123xyz789"), s + " -> " + out);
        }
        String ok = "payment 5f1c2d3e-aaaa-bbbb-cccc-1234567890ab amount=10000 status=SUCCESS";
        assertEquals(ok, LogMasker.mask(ok), "ordinary fields stay readable");
    }

    @Test void cardNumbersAreMaskedButOtherNumbersAreNot() {
        assertFalse(LogMasker.mask("card 4111 1111 1111 1111 declined").contains("4111"));
        assertFalse(LogMasker.mask("pan=5555555555554444").contains("5555555555554444"));
        assertFalse(LogMasker.mask("card 4111-1111-1111-1111").contains("1111-1111"));
        assertEquals("ts 1760000000000 order 1234567890123456", LogMasker.mask("ts 1760000000000 order 1234567890123456"), "not Luhn-valid");
        assertEquals("amount 10000", LogMasker.mask("amount 10000"));
        assertNull(LogMasker.mask(null));
    }

    // ------------------------------------------------------------------ JSON logging in the prod profile

    /** Runs a tiny Spring Boot app in a fresh JVM (logging is initialised once per JVM) and returns everything it printed. */
    private static String runApp(String profile) throws Exception {
        String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"), JsonLogMain.class.getName(), "--spring.profiles.active=" + profile);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "child JVM did not finish");
        return out;
    }

    @Test void prodProfileWritesMaskedStructuredJsonWithCorrelationId() throws Exception {
        String out = runApp("prod");
        assertFalse(out.contains("4111111111111111") || out.contains("ab12cd34ef56") || out.contains("hunter2hunter2"), "secrets must not reach the log: " + out);
        ObjectMapper m = new ObjectMapper();
        JsonNode line = null;
        for (String l : out.split("\\R")) {
            if (l.startsWith("{") && l.contains("charged card")) line = m.readTree(l);
        }
        assertNotNull(line, "expected one JSON line, got: " + out);
        assertEquals("req_json_test_1", line.path("correlationId").asText(), line.toString());
        assertTrue(line.path("message").asText().contains("***"));
        assertEquals("apg.test", line.path("logger_name").asText());
    }

    @Test void defaultProfileIsReadablePlainTextWithCorrelationIdAndStillMasked() throws Exception {
        String out = runApp("local");
        assertFalse(out.contains("4111111111111111") || out.contains("ab12cd34ef56") || out.contains("hunter2hunter2"), "secrets must not reach the log: " + out);
        String line = java.util.Arrays.stream(out.split("\\R")).filter(l -> l.contains("charged card")).findFirst().orElseThrow(() -> new AssertionError(out));
        assertTrue(line.contains("[log-test,") && line.contains("req_json_test_1"), line);
        assertTrue(line.contains("***"), line);
    }

    // ------------------------------------------------------------------ outbound propagation

    @Test void outboundCallsCarryCorrelationAndTraceparentWithoutOverridingExplicitOnes() throws Exception {
        ClientHttpRequestExecution exec = (r, b) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        String trace = UUID.randomUUID().toString().replace("-", ""), span = trace.substring(0, 16);
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://peer/x"));
        MDC.put(CorrelationIdFilter.MDC_KEY, "req_outbound_1");
        MDC.put("traceId", trace);
        MDC.put("spanId", span);
        try {
            OutboundHeaders.INSTANCE.intercept(req, new byte[0], exec);
            assertEquals("req_outbound_1", req.getHeaders().getFirst("X-Correlation-Id"));
            assertEquals("00-" + trace + "-" + span + "-01", req.getHeaders().getFirst("traceparent"));

            MockClientHttpRequest explicit = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://peer/y"));
            explicit.getHeaders().set("X-Correlation-Id", "mine");
            OutboundHeaders.INSTANCE.intercept(explicit, new byte[0], exec);
            assertEquals("mine", explicit.getHeaders().getFirst("X-Correlation-Id"));
        } finally {
            MDC.clear();
        }
        MockClientHttpRequest bare = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://peer/z"));
        OutboundHeaders.INSTANCE.intercept(bare, new byte[0], exec);
        assertNull(bare.getHeaders().getFirst("X-Correlation-Id"), "no correlation id => nothing invented");
        assertNull(bare.getHeaders().getFirst("traceparent"));
    }
}
