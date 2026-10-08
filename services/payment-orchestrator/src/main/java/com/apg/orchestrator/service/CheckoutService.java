package com.apg.orchestrator.service;

import com.apg.common.api.ApiException;
import com.apg.orchestrator.client.PaymentClient;
import com.apg.orchestrator.client.PaymentClient.Attempt;
import com.apg.orchestrator.client.PaymentClient.Payment;
import com.apg.orchestrator.client.ProviderClient;
import com.apg.orchestrator.client.ProviderClient.Checkout;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CheckoutService {
    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    public record Started(UUID paymentId, String status, String provider, String checkoutUrl, Instant expiresAt) {}

    private final PaymentClient payments;
    private final ProviderClient providers;
    private final RoutingService routing;
    private final CheckoutTokens tokens;
    private final OrchestratorProperties props;

    public CheckoutService(PaymentClient payments, ProviderClient providers, RoutingService routing, CheckoutTokens tokens,
                           OrchestratorProperties props) {
        this.payments = payments;
        this.providers = providers;
        this.routing = routing;
        this.tokens = tokens;
        this.props = props;
    }

    /**
     * Starts a provider attempt for a CREATED payment and returns the hosted redirect URL for the customer.
     * Nothing is sent to the provider yet; the customer's browser goes there from the redirect page.
     */
    public Started start(UUID merchantId, UUID paymentId) {
        Payment p = payments.payment(paymentId);
        if (!p.merchantId().equals(merchantId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found");   // no cross-merchant hints
        }
        RoutingService.Choice choice = routing.choose();
        Attempt attempt = payments.begin(paymentId, choice.provider());     // 409 if the payment is not CREATED; CREATED -> PENDING
        try {
            providers.checkout(choice.provider(), paymentRequest(p, attempt.providerReference()));   // validates only; builds hash locally
            routing.record(paymentId, attempt.providerReference(), choice);
            CheckoutTokens.Issued t = tokens.issue(attempt.providerReference());
            return new Started(paymentId, "PENDING", choice.provider(), props.publicBaseUrl() + "/checkout/" + t.token(), t.expiresAt());
        } catch (RuntimeException e) {
            // Nothing reached the provider, so failing is safe and frees the order for a new payment.
            log.warn("checkout preparation failed for payment {}: {}", paymentId, e.toString());
            try {
                payments.updateAttempt(attempt.providerReference(), "FAILED", null, "CHECKOUT_PREPARATION");
                payments.transition(paymentId, "FAILED", "could not prepare provider checkout", "ORCHESTRATOR");
            } catch (RuntimeException inner) {
                log.error("could not mark payment {} failed after checkout error: {}", paymentId, inner.toString());
            }
            throw e;
        }
    }

    /** What the redirect page needs to render: the provider form for this attempt. Moves PENDING -> PROCESSING. */
    public Checkout redirectInstructions(String token) {
        String reference = tokens.verify(token);
        Attempt attempt = payments.attempt(reference);
        Payment p = payments.payment(attempt.paymentId());
        if (!"PENDING".equals(p.status()) && !"PROCESSING".equals(p.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_PAYABLE", "This payment can no longer be paid (" + p.status() + ")");
        }
        Checkout c = providers.checkout(attempt.provider(), paymentRequest(p, reference));
        if ("PENDING".equals(p.status())) {
            payments.transition(p.paymentId(), "PROCESSING", "customer redirected to " + attempt.provider(), "ORCHESTRATOR");
            payments.updateAttempt(reference, "REDIRECTED", null, null);
        }
        return c;
    }

    private Map<String, Object> paymentRequest(Payment p, String reference) {
        String callback = props.publicBaseUrl() + "/api/v1/webhooks/payu";   // provider id resolved by route; one provider today
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("providerReference", reference);
        m.put("amountMinor", p.amount());
        m.put("currency", p.currency());
        m.put("description", p.description());
        m.put("customerName", p.customerName());
        m.put("customerEmail", p.customerEmail());
        m.put("customerPhone", p.customerPhone());
        m.put("paymentId", p.paymentId().toString());
        m.put("successUrl", callback);
        m.put("failureUrl", callback);
        return m;
    }
}
