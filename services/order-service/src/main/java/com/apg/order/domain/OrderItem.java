package com.apg.order.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "order_items")
public class OrderItem {
    @Id private UUID id;
    private UUID orderId;
    private String name;
    private int quantity;
    private long unitAmountMinor;

    protected OrderItem() {}

    public OrderItem(UUID orderId, String name, int quantity, long unitAmountMinor) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.name = name;
        this.quantity = quantity;
        this.unitAmountMinor = unitAmountMinor;
    }

    public String getName() { return name; }
    public int getQuantity() { return quantity; }
    public long getUnitAmountMinor() { return unitAmountMinor; }
}
