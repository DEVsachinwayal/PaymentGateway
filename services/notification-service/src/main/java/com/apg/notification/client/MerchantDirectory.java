package com.apg.notification.client;

import com.apg.notification.service.NotificationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Asks merchant-service where an event must be delivered and with which secret it must be signed. */
@Component
public class MerchantDirectory {
    public record Subscriber(UUID id, String url, String signingSecret) {}

    private final RestClient client;

    public MerchantDirectory(NotificationProperties props, @Value("${apg.internal.token}") String token) {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        f.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().requestInterceptor(com.apg.common.web.OutboundHeaders.INSTANCE).baseUrl(props.merchantServiceUri()).requestFactory(f).defaultHeader("X-Internal-Token", token).build();
    }

    /** Throws when merchant-service cannot answer: the caller must retry, never assume "no subscribers". */
    public List<Subscriber> subscribers(UUID merchantId, String event) {
        JsonNode data = client.get().uri("/internal/v1/merchants/{m}/webhook-subscribers?event={e}", merchantId, event)
                .retrieve().body(JsonNode.class).path("data");
        List<Subscriber> out = new ArrayList<>();
        for (JsonNode n : data) out.add(new Subscriber(UUID.fromString(n.path("id").asText()), n.path("url").asText(), n.path("signingSecret").asText()));
        return out;
    }
}
