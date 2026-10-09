package com.apg.order.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public final class Repositories {
    private Repositories() {}

    public interface OrderRepository extends JpaRepository<Order, UUID> {
        Optional<Order> findByIdAndMerchantId(UUID id, UUID merchantId);
        Page<Order> findByMerchantId(UUID merchantId, Pageable pageable);
    }

    public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {
        List<OrderItem> findByOrderId(UUID orderId);
        List<OrderItem> findByOrderIdIn(java.util.Collection<UUID> orderIds);
    }

    public interface CustomerRepository extends JpaRepository<Customer, UUID> {
        @Query("select c from Customer c where c.merchantId = ?1 and lower(c.email) = lower(?2)")
        Optional<Customer> findByMerchantAndEmail(UUID merchantId, String email);
    }
}
