package com.apg.payment.web;

import com.apg.payment.domain.PaymentStatus;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Note what is NOT here: amount, currency and status. Amount/currency come from the order; status is owned
 * by the state machine. Unknown JSON properties are rejected (fail-on-unknown-properties).
 */
public final class PaymentDtos {
    private PaymentDtos() {}

    public record CreatePaymentRequest(
            @NotNull UUID orderId,
            @Pattern(regexp = "^(UPI|CARD|NETBANKING|WALLET)$", message = "one of UPI, CARD, NETBANKING, WALLET") String paymentMethod,
            @Size(max = 500) @Pattern(regexp = "^https://\\S+$", message = "must be an https URL") String returnUrl) {}

    public record PaymentView(UUID id, UUID orderId, long amount, String currency, String status, String paymentMethod,
                              String returnUrl, Instant createdAt, Instant updatedAt) {}

    public record HistoryItem(String from, String to, String reason, String source, Instant at) {}

    public record StatusView(UUID paymentId, String status, Instant updatedAt, List<HistoryItem> history) {}

    public record TransitionRequest(@NotNull PaymentStatus to, @Size(max = 300) String reason,
                                    @NotBlank @Pattern(regexp = "^(ORCHESTRATOR|WEBHOOK|VERIFICATION|OPS)$") String source) {}

    public record PageView<T>(List<T> content, int page, int size, long totalElements) {}
}
