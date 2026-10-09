package com.apg.orchestrator.client;

import com.apg.common.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Shared plumbing for service-to-service calls: timeouts, internal token, uniform error mapping. */
abstract class InternalHttp {
    protected final RestClient client;
    private final ObjectMapper mapper;

    protected InternalHttp(String baseUrl, String internalToken, ObjectMapper mapper, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(http);
        f.setReadTimeout(readTimeout);
        this.client = RestClient.builder().requestInterceptor(com.apg.common.web.OutboundHeaders.INSTANCE).baseUrl(baseUrl).requestFactory(f).defaultHeader("X-Internal-Token", internalToken).build();
        this.mapper = mapper;
    }

    /** 4xx/5xx from the peer keep their status and error code; network trouble becomes 503 DEPENDENCY_UNAVAILABLE. */
    protected <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpStatusCodeException e) {
            String code = "UPSTREAM_ERROR";
            String msg = "Upstream error";
            try {
                JsonNode err = mapper.readTree(e.getResponseBodyAsString()).path("error");
                if (err.hasNonNull("code")) code = err.get("code").asText();
                if (err.hasNonNull("message")) msg = err.get("message").asText();
            } catch (Exception ignored) { }
            throw new ApiException(HttpStatus.valueOf(e.getStatusCode().value()), code, msg);
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "A dependent service is unavailable");
        }
    }
}
