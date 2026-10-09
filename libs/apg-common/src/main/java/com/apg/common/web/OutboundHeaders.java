package com.apg.common.web;

import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * For calls from one APG service to another: forwards the correlation id and the current trace (W3C traceparent) so one request can
 * be followed across services in logs and in the trace backend. NEVER attach this to calls to third parties (PayU, merchants):
 * internal ids must not leave the platform.
 */
public final class OutboundHeaders implements ClientHttpRequestInterceptor {
    public static final OutboundHeaders INSTANCE = new OutboundHeaders();

    private OutboundHeaders() {}

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        String correlation = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlation != null && !request.getHeaders().containsKey(CorrelationIdFilter.HEADER)) {
            request.getHeaders().set(CorrelationIdFilter.HEADER, correlation);
        }
        String trace = MDC.get("traceId");
        String span = MDC.get("spanId");
        if (trace != null && span != null && trace.length() == 32 && span.length() == 16 && !request.getHeaders().containsKey("traceparent")) {
            request.getHeaders().set("traceparent", "00-" + trace + "-" + span + "-01");
        }
        return execution.execute(request, body);
    }
}
