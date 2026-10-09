package com.apg.payment.service;

import com.apg.common.api.ApiException;
import com.apg.payment.domain.*;
import com.apg.payment.domain.Repositories.*;
import com.apg.payment.web.PaymentDtos.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PaymentService {
    private final PaymentRepository payments;
    private final PaymentIntentRepository intents;
    private final StateHistoryRepository history;
    private final PaymentStateService states;
    private final OrderClient orders;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, PaymentIntentRepository intents, StateHistoryRepository history,
                          PaymentStateService states, OrderClient orders, Clock clock) {
        this.payments = payments;
        this.intents = intents;
        this.history = history;
        this.states = states;
        this.orders = orders;
        this.clock = clock;
    }

    public PaymentView create(UUID merchantId, CreatePaymentRequest r) {
        OrderClient.OrderInfo order = orders.fetch(r.orderId(), merchantId);
        if (!"CREATED".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_PAYABLE", "Order is " + order.status() + ", not payable");
        }
        Instant now = clock.instant();
        PaymentIntent intent = intents.save(new PaymentIntent(merchantId, order.id(), order.amount(), order.currency(),
                r.paymentMethod(), r.returnUrl(), order.description(), order.customerName(), order.customerEmail(),
                order.customerPhone(), now));
        Payment p = new Payment(intent.getId(), merchantId, order.id(), order.amount(), order.currency(), now);
        try {
            payments.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_ALREADY_EXISTS",
                    "This order already has an open or successful payment");
        }
        states.recordCreated(p, "API");
        return view(p, intent);
    }

    @Transactional(readOnly = true)
    public PaymentView get(UUID merchantId, UUID id) {
        Payment p = owned(merchantId, id);
        return view(p, intents.findById(p.getIntentId()).orElseThrow());
    }

    @Transactional(readOnly = true)
    public StatusView status(UUID merchantId, UUID id) {
        Payment p = owned(merchantId, id);
        List<HistoryItem> items = history.findByPaymentIdOrderByIdAsc(id).stream()
                .map(h -> new HistoryItem(h.getFromStatus() == null ? null : h.getFromStatus().name(),
                        h.getToStatus().name(), h.getReason(), h.getSource(), h.getCreatedAt())).toList();
        return new StatusView(p.getId(), p.getStatus().name(), p.getUpdatedAt(), items);
    }

    @Transactional(readOnly = true)
    public PageView<PaymentView> list(UUID merchantId, int page, int size) {
        var p = payments.findByMerchantId(merchantId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        // one batch query for the intents of the whole page instead of one per payment
        var byId = intents.findAllById(p.getContent().stream().map(Payment::getIntentId).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(PaymentIntent::getId, i -> i));
        return new PageView<>(p.getContent().stream()
                .map(x -> view(x, java.util.Optional.ofNullable(byId.get(x.getIntentId())).orElseThrow())).toList(),
                p.getNumber(), p.getSize(), p.getTotalElements());
    }

    private Payment owned(UUID merchantId, UUID id) {
        return payments.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found"));
    }

    private static PaymentView view(Payment p, PaymentIntent i) {
        return new PaymentView(p.getId(), p.getOrderId(), p.getAmountMinor(), p.getCurrency(), p.getStatus().name(),
                i.getPaymentMethod(), i.getReturnUrl(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
