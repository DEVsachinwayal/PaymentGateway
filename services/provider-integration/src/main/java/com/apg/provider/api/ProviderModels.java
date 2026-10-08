package com.apg.provider.api;

import jakarta.validation.constraints.*;
import java.util.Map;

/** Provider-neutral contracts. Provider specific DTOs never leave their adapter package. */
public final class ProviderModels {
    private ProviderModels() {}

    /** Outcome of a payment as understood by APG, independent of the provider's vocabulary. */
    public enum Status { SUCCESS, FAILED, PENDING, NOT_FOUND, UNKNOWN }

    public record PaymentRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{8,25}$") String providerReference,   // our attempt reference (txnid)
            @Positive long amountMinor,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @Size(max = 500) String description,
            @Size(max = 200) String customerName,
            @Size(max = 254) String customerEmail,
            @Size(max = 20) String customerPhone,
            @Size(max = 64) String paymentId,                                              // echoed back for correlation
            @NotBlank @Pattern(regexp = "^https://\\S+$|^http://localhost\\S*$") String successUrl,
            @NotBlank @Pattern(regexp = "^https://\\S+$|^http://localhost\\S*$") String failureUrl) {}

    /** What the browser must do to reach the provider's hosted checkout. */
    public record CheckoutInstructions(String method, String url, Map<String, String> fields) {}

    public record PaymentStatusResponse(Status status, String providerPaymentId, Long amountMinor, String rawStatus,
                                        String errorCode, String errorMessage) {}

    public record RefundRequest(
            @NotBlank @Size(max = 100) String providerPaymentId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{6,23}$") String refundReference,
            @Positive long amountMinor) {}

    public enum RefundStatus { ACCEPTED, REJECTED, UNKNOWN }

    public record RefundResponse(RefundStatus status, String providerRequestId, String message) {}

    /** PROCESSING = queued/unknown at the provider; SUCCESS/FAILED only when the provider says so explicitly. */
    public enum RefundOutcome { SUCCESS, FAILED, PROCESSING }

    public record RefundStatusRequest(@NotBlank @Size(max = 100) String providerRequestId,
                                      @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{6,23}$") String refundReference) {}

    public record RefundStatusResponse(RefundOutcome outcome, String rawStatus) {}

    public record ProviderWebhookRequest(@NotNull Map<String, String> fields) {}

    /** {@code authentic} only proves the provider produced the message; callers must still confirm via status API. */
    public record ProviderWebhookResult(boolean authentic, String reason, String providerReference,
                                        String providerPaymentId, Status status, Long amountMinor) {}

    public record ProviderHealth(boolean up, long latencyMs, String detail) {}
}
