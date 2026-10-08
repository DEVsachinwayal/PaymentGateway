package com.apg.payment.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public final class Repositories {
    private Repositories() {}

    public interface PaymentRepository extends JpaRepository<Payment, UUID> {
        Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);
        Page<Payment> findByMerchantId(UUID merchantId, Pageable pageable);
    }

    public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {}

    public interface StateHistoryRepository extends JpaRepository<StateHistory, Long> {
        List<StateHistory> findByPaymentIdOrderByIdAsc(UUID paymentId);
    }

    public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {}
}
