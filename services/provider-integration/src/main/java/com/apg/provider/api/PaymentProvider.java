package com.apg.provider.api;

import com.apg.provider.api.ProviderModels.*;

/**
 * Port every payment provider adapter implements (spec 9). The core payment domain depends on this
 * interface only; provider specific classes stay inside their own package.
 */
public interface PaymentProvider {
    /** Stable lowercase id, e.g. "payu". */
    String id();

    /** Builds the hosted-checkout instructions for one attempt. No network call is required. */
    CheckoutInstructions createPayment(PaymentRequest request);

    /**
     * Asks the provider for the authoritative state of an attempt. NOTE: keyed by OUR provider reference
     * (PayU "txnid"), because that is what PayU's verify API accepts.
     * @throws ProviderException when the provider cannot be reached or answers unintelligibly (=> outcome UNKNOWN)
     */
    PaymentStatusResponse getPaymentStatus(String providerReference);

    RefundResponse refund(RefundRequest request);

    /** Asks the provider how a previously accepted refund ended. @throws ProviderException on provider trouble. */
    RefundStatusResponse getRefundStatus(RefundStatusRequest request);

    /** Authenticates a callback/webhook (signature or hash) and normalises it. Never marks anything paid by itself. */
    ProviderWebhookResult verifyWebhook(ProviderWebhookRequest request);

    ProviderHealth health();
}
