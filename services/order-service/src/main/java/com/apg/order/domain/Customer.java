package com.apg.order.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer {
    @Id private UUID id;
    private UUID merchantId;
    private String name;
    private String email;
    private String phone;
    private Instant createdAt;

    protected Customer() {}

    public Customer(UUID merchantId, String name, String email, String phone, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.name = name;
        this.email = email == null ? null : email.trim().toLowerCase();
        this.phone = phone;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
}
