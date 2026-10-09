package com.apg.payment.service;

import com.apg.common.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Reads order data from order-service (the owner of orders). Amount and currency always come from here. */
@Component
public class OrderClient {
    public record OrderInfo(UUID id, UUID merchantId, long amount, String currency, String status, String description,
                            String customerName, String customerEmail, String customerPhone) {}

    private static final Logger log = LoggerFactory.getLogger(OrderClient.class);
    private final RestClient client;

    public OrderClient(PaymentProperties props, @Value("${apg.internal.token}") String internalToken) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder().requestInterceptor(com.apg.common.web.OutboundHeaders.INSTANCE).baseUrl(props.orderServiceUri()).requestFactory(factory)
                .defaultHeader("X-Internal-Token", internalToken).build();
    }

    public OrderInfo fetch(UUID orderId, UUID merchantId) {
        try {
            JsonNode n = client.get().uri("/internal/v1/orders/{id}?merchantId={m}", orderId, merchantId)
                    .retrieve().body(JsonNode.class);
            JsonNode d = n.path("data");
            return new OrderInfo(UUID.fromString(d.path("id").asText()), UUID.fromString(d.path("merchantId").asText()),
                    d.path("amount").asLong(), d.path("currency").asText(), d.path("status").asText(),
                    text(d, "description"), text(d, "customerName"), text(d, "customerEmail"), text(d, "customerPhone"));
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
        } catch (Exception e) {
            log.warn("order-service call failed: {}", e.toString());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ORDER_SERVICE_UNAVAILABLE", "Order service unavailable");
        }
    }

    private static String text(JsonNode d, String field) {
        JsonNode v = d.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
