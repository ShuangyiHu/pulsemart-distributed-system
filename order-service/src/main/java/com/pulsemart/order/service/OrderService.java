package com.pulsemart.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsemart.order.api.dto.PlaceOrderRequest;
import com.pulsemart.order.api.dto.PlaceOrderRequest.OrderItemRequest;
import com.pulsemart.order.domain.*;
import com.pulsemart.order.repository.OrderRepository;
import com.pulsemart.order.repository.OutboxRepository;
import com.pulsemart.shared.event.EventEnvelope;
import com.pulsemart.shared.event.EventType;
import com.pulsemart.shared.event.payload.OrderCancelledPayload;
import com.pulsemart.shared.event.payload.OrderCompletedPayload;
import com.pulsemart.shared.event.payload.OrderCreatedPayload;
import com.pulsemart.shared.event.payload.PaymentInitiatedPayload;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Transactional
    public Order placeOrder(PlaceOrderRequest request) {
        // 1. Build and persist the order
        Order order = Order.builder()
                .customerId(request.getCustomerId())
                .status(OrderStatus.PENDING)
                .totalAmount(calculateTotal(request.getItems()))
                .build();

        for (OrderItemRequest itemReq : request.getItems()) {
            OrderItem item = OrderItem.builder()
                    .order(order)
                    .productId(itemReq.getProductId())
                    .quantity(itemReq.getQuantity())
                    .unitPrice(itemReq.getUnitPrice())
                    .build();
            order.getItems().add(item);
        }
        orderRepository.save(order);

        // 2. Write outbox row in the SAME transaction
        OrderCreatedPayload payload = OrderCreatedPayload.builder()
                .orderId(order.getId())
                .customerId(order.getCustomerId())
                .totalAmount(order.getTotalAmount())
                .items(request.getItems().stream()
                        .map(i -> OrderCreatedPayload.OrderItemPayload.builder()
                                .productId(i.getProductId())
                                .quantity(i.getQuantity())
                                .unitPrice(i.getUnitPrice())
                                .build())
                        .toList())
                .build();

        saveOutboxEvent(EventType.ORDER_CREATED.name(), order.getId(), payload);

        log.info("Order placed: orderId={} customerId={}", order.getId(), order.getCustomerId());
        meterRegistry.counter("pulsemart.orders.placed").increment();
        return order;
    }

    @Transactional
    public void markInventoryReserved(UUID orderId, UUID reservationId) {
        Order order = getOrder(orderId);
        order.setStatus(OrderStatus.INVENTORY_RESERVED);
        order.setReservationId(reservationId);
        orderRepository.save(order);

        PaymentInitiatedPayload payload = PaymentInitiatedPayload.builder()
                .orderId(orderId)
                .customerId(order.getCustomerId())
                .amount(order.getTotalAmount())
                .reservationId(reservationId)
                .build();

        saveOutboxEvent(EventType.PAYMENT_INITIATED.name(), orderId, payload);
        log.info("Inventory reserved, payment initiated: orderId={}", orderId);
    }

    @Transactional
    public void markPaymentPending(UUID orderId) {
        Order order = getOrder(orderId);
        order.setStatus(OrderStatus.PAYMENT_PENDING);
        orderRepository.save(order);
        log.info("Order status → PAYMENT_PENDING: orderId={}", orderId);
    }

    @Transactional
    public void markCompleted(UUID orderId, UUID paymentId) {
        Order order = getOrder(orderId);
        order.setStatus(OrderStatus.COMPLETED);
        orderRepository.save(order);

        OrderCompletedPayload payload = OrderCompletedPayload.builder()
                .orderId(orderId)
                .customerId(order.getCustomerId())
                .totalAmount(order.getTotalAmount())
                .paymentId(paymentId)
                .build();

        saveOutboxEvent(EventType.ORDER_COMPLETED.name(), orderId, payload);

        meterRegistry.counter("pulsemart.orders.total", "status", "completed").increment();
        log.info("Order COMPLETED: orderId={}", orderId);
    }

    @Transactional
    public void markCancelled(UUID orderId, String reason) {
        Order order = getOrder(orderId);
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);

        OrderCancelledPayload payload = OrderCancelledPayload.builder()
                .orderId(orderId)
                .customerId(order.getCustomerId())
                .totalAmount(order.getTotalAmount())
                .cancellationReason(reason)
                .reservationId(order.getReservationId())
                .build();

        saveOutboxEvent(EventType.ORDER_CANCELLED.name(), orderId, payload);

        meterRegistry.counter("pulsemart.orders.total", "status", "cancelled").increment();
        log.info("Order CANCELLED: orderId={} reason={}", orderId, reason);
    }

    @Transactional(readOnly = true)
    public Order getOrder(UUID orderId) {
        return orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    @Transactional(readOnly = true)
    public List<Order> getOrdersByCustomer(UUID customerId) {
        return orderRepository.findByCustomerId(customerId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void saveOutboxEvent(String eventType, UUID aggregateId, Object payload) {
        try {
            OutboxEvent outbox = OutboxEvent.builder()
                    .eventId(UUID.randomUUID())
                    .eventType(eventType)
                    .aggregateId(aggregateId)
                    .payload(objectMapper.writeValueAsString(
                            EventEnvelope.builder()
                                    .eventId(UUID.randomUUID())
                                    .eventType(eventType)
                                    .timestamp(Instant.now())
                                    .sourceService("order-service")
                                    .version("1")
                                    .payload(payload)
                                    .build()))
                    .status(OutboxStatus.PENDING)
                    .build();
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox payload for " + eventType, e);
        }
    }

    private BigDecimal calculateTotal(List<OrderItemRequest> items) {
        return items.stream()
                .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
