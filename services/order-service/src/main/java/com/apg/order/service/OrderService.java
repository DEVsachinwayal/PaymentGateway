package com.apg.order.service;

import com.apg.common.api.ApiException;
import com.apg.order.domain.*;
import com.apg.order.domain.Repositories.*;
import com.apg.order.web.OrderDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrderService {
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final CustomerRepository customers;
    private final OrderProperties props;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OrderService(OrderRepository orders, OrderItemRepository items, CustomerRepository customers,
                        OrderProperties props, ObjectMapper mapper, Clock clock) {
        this.orders = orders;
        this.items = items;
        this.customers = customers;
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
    }

    public OrderView create(UUID merchantId, CreateOrderRequest r) {
        if (!props.allowedCurrencies().contains(r.currency())) {
            throw bad("CURRENCY_NOT_SUPPORTED", "Supported currencies: " + props.allowedCurrencies());
        }
        if (r.amount() > props.maxAmountMinor()) {
            throw bad("AMOUNT_TOO_LARGE", "Amount exceeds the maximum allowed");
        }
        List<ItemInput> lines = r.items() == null ? List.of() : r.items();
        if (!lines.isEmpty()) {
            long sum = 0;
            try {
                for (ItemInput i : lines) sum = Math.addExact(sum, Math.multiplyExact(i.unitAmount(), (long) i.quantity()));
            } catch (ArithmeticException e) {
                throw bad("AMOUNT_MISMATCH", "Item totals overflow");
            }
            if (sum != r.amount()) {
                throw bad("AMOUNT_MISMATCH", "Sum of items (" + sum + ") does not equal amount (" + r.amount() + ")");
            }
        }
        Map<String, String> meta = r.metadata() == null ? Map.of() : r.metadata();
        for (var e : meta.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getKey().length() > 40
                    || e.getValue() == null || e.getValue().length() > 500) {
                throw bad("INVALID_METADATA", "Metadata keys max 40 chars, values max 500 chars");
            }
        }
        String ref = r.merchantOrderRef() == null || r.merchantOrderRef().isBlank() ? null : r.merchantOrderRef();

        Instant now = clock.instant();
        Customer customer = resolveCustomer(merchantId, r.customer(), now);
        Order o = new Order(merchantId, ref, customer == null ? null : customer.getId(), r.amount(), r.currency(),
                r.description(), json(meta), now);
        try {
            orders.saveAndFlush(o);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_ORDER_REF", "merchantOrderRef already used");
        }
        List<OrderItem> saved = items.saveAll(lines.stream()
                .map(i -> new OrderItem(o.getId(), i.name(), i.quantity(), i.unitAmount())).toList());
        return view(o, customer, saved);
    }

    @Transactional(readOnly = true)
    public OrderView get(UUID merchantId, UUID id) {
        Order o = orders.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found"));
        return view(o, o.getCustomerId() == null ? null : customers.findById(o.getCustomerId()).orElse(null),
                items.findByOrderId(o.getId()));
    }

    @Transactional(readOnly = true)
    public PageView<OrderView> list(UUID merchantId, int page, int size) {
        var p = orders.findByMerchantId(merchantId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        List<OrderView> content = p.getContent().stream().map(o -> view(o,
                o.getCustomerId() == null ? null : customers.findById(o.getCustomerId()).orElse(null),
                items.findByOrderId(o.getId()))).toList();
        return new PageView<>(content, p.getNumber(), p.getSize(), p.getTotalElements());
    }

    private Customer resolveCustomer(UUID merchantId, CustomerInput in, Instant now) {
        if (in == null || (in.name() == null && in.email() == null && in.phone() == null)) return null;
        if (in.email() != null) {
            var existing = customers.findByMerchantAndEmail(merchantId, in.email());
            if (existing.isPresent()) return existing.get();
        }
        return customers.save(new Customer(merchantId, in.name(), in.email(), in.phone(), now));
    }

    private OrderView view(Order o, Customer c, List<OrderItem> lines) {
        Map<String, String> meta;
        try {
            meta = mapper.readValue(o.getMetadata(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            meta = Map.of();
        }
        return new OrderView(o.getId(), o.getMerchantOrderRef(), o.getAmountMinor(), o.getCurrency(), o.getStatus().name(),
                o.getDescription(),
                c == null ? null : new CustomerView(c.getId(), c.getName(), c.getEmail(), c.getPhone()),
                lines.stream().map(i -> new ItemView(i.getName(), i.getQuantity(), i.getUnitAmountMinor())).toList(),
                meta, o.getCreatedAt());
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException bad(String code, String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, msg);
    }
}
