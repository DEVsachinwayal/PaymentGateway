package com.apg.common.web;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

class OutboundHeadersTest {
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
