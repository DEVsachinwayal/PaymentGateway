package com.apg.settlement.client;

import com.apg.settlement.service.SettlementProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Read-only lookups into APG's own records (payment attempt by provider reference, refund by provider token). */
@Component
public class Peers {
    public record PaymentInfo(UUID paymentId, UUID merchantId, String provider, String providerPaymentId, long amount,
                              String currency, String paymentStatus) {}

    public record RefundInfo(UUID refundId, UUID merchantId, UUID paymentId, long amount, String currency, String status, String provider) {}

    /** Thrown when a lookup could not be answered (dependency down / unexpected answer): the item stays unevaluated. */
    public static class LookupUnavailable extends RuntimeException {
        public LookupUnavailable(String message, Throwable cause) { super(message, cause); }
    }

    private final RestClient payments;
    private final RestClient refunds;

    public Peers(SettlementProperties props, @Value("${apg.internal.token}") String token) {
        this.payments = build(props.paymentServiceUri(), token);
        this.refunds = build(props.refundServiceUri(), token);
    }

    private static RestClient build(String base, String token) {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        f.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(base).requestFactory(f).defaultHeader("X-Internal-Token", token).build();
    }

    public Optional<PaymentInfo> paymentByReference(String providerReference) {
        return lookup(payments, "/internal/v1/attempts/{r}", providerReference).map(d -> new PaymentInfo(
                UUID.fromString(d.path("paymentId").asText()), UUID.fromString(d.path("merchantId").asText()), text(d, "provider"),
                text(d, "providerPaymentId"), d.path("amount").asLong(), d.path("currency").asText(), d.path("paymentStatus").asText()));
    }

    public Optional<RefundInfo> refundByReference(String token) {
        return lookup(refunds, "/internal/v1/refunds/by-reference/{r}", token).map(d -> new RefundInfo(
                UUID.fromString(d.path("refundId").asText()), UUID.fromString(d.path("merchantId").asText()),
                UUID.fromString(d.path("paymentId").asText()), d.path("amount").asLong(), d.path("currency").asText(),
                d.path("status").asText(), text(d, "provider")));
    }

    /** 404 => empty (APG has no such record: a real finding). Any other trouble => LookupUnavailable (not a finding). */
    private Optional<JsonNode> lookup(RestClient client, String uri, String ref) {
        try {
            var res = client.get().uri(uri, ref).retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(JsonNode.class);
            HttpStatusCode code = res.getStatusCode();
            if (code.value() == 404) return Optional.empty();
            if (!code.is2xxSuccessful() || res.getBody() == null || !res.getBody().path("data").isObject()) {
                throw new LookupUnavailable("unexpected answer " + code.value(), null);
            }
            return Optional.of(res.getBody().path("data"));
        } catch (LookupUnavailable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LookupUnavailable("lookup failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
