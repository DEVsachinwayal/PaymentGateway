package com.apg.order.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OrderDtos {
    private OrderDtos() {}

    public record CustomerInput(
            @Size(max = 200) String name,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "phone must be 7-15 digits") String phone) {}

    public record ItemInput(
            @NotBlank @Size(max = 200) String name,
            @Min(1) @Max(100000) int quantity,
            @Min(0) long unitAmount) {}

    /** {@code amount} is in minor units (paise for INR): 49900 = INR 499.00. */
    public record CreateOrderRequest(
            @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9_.:-]*$", message = "only letters, digits and _ . : -") String merchantOrderRef,
            @Positive long amount,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "ISO-4217 currency code") String currency,
            @Size(max = 500) String description,
            @Valid CustomerInput customer,
            @Valid @Size(max = 100) List<ItemInput> items,
            @Size(max = 20) Map<String, String> metadata) {}

    public record CustomerView(UUID id, String name, String email, String phone) {}

    public record ItemView(String name, int quantity, long unitAmount) {}

    public record OrderView(UUID id, String merchantOrderRef, long amount, String currency, String status,
                            String description, CustomerView customer, List<ItemView> items,
                            Map<String, String> metadata, Instant createdAt) {}

    public record PageView<T>(List<T> content, int page, int size, long totalElements) {}
}
