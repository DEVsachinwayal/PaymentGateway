package com.apg.payment.web;

import com.apg.payment.domain.PaymentAttempt;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Contracts for orchestrator / webhook / verification callers (never exposed to merchants). */
public final class InternalDtos {
    private InternalDtos() {}

    public record InternalPayment(UUID paymentId, UUID merchantId, UUID orderId, long amount, String currency, String status,
                                  String provider, String providerPaymentId, String paymentMethod, String returnUrl,
                                  String description, String customerName, String customerEmail, String customerPhone,
                                  Instant updatedAt) {}

    public record BeginAttemptRequest(@NotNull @Pattern(regexp = "^[a-z0-9_]{2,30}$") String provider) {}

    public record AttemptView(UUID attemptId, UUID paymentId, UUID merchantId, int attemptNo, String provider,
                              String providerReference, String providerPaymentId, String status, long amount,
                              String currency, String paymentStatus) {}

    public record UpdateAttemptRequest(@NotNull PaymentAttempt.Status status,
                                       @Size(max = 100) String providerPaymentId,
                                       @Size(max = 60) String failureCode) {}

    public record PendingItem(UUID paymentId, String paymentStatus, Instant updatedAt, String provider, String providerReference) {}
}
