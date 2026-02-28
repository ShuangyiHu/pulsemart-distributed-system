package com.pulsemart.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulsemart.order.api.dto.PlaceOrderRequest;
import com.pulsemart.order.domain.Order;
import com.pulsemart.order.domain.OrderStatus;
import com.pulsemart.order.domain.OutboxEvent;
import com.pulsemart.order.domain.OutboxStatus;
import com.pulsemart.order.repository.OrderRepository;
import com.pulsemart.order.repository.OutboxRepository;
import com.pulsemart.shared.event.EventType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxRepository outboxRepository;

    private ObjectMapper objectMapper;
    private MeterRegistry meterRegistry;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        meterRegistry = new SimpleMeterRegistry();
        orderService = new OrderService(orderRepository, outboxRepository, objectMapper, meterRegistry);
    }

    // ── placeOrder ────────────────────────────────────────────────────────────

    @Test
    void placeOrder_shouldSaveOrderWithPendingStatus() {
        PlaceOrderRequest request = buildRequest(1, new BigDecimal("49.99"));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Order result = orderService.placeOrder(request);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(result.getCustomerId()).isEqualTo(request.getCustomerId());
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void placeOrder_shouldCalculateTotalAsQuantityTimesUnitPrice() {
        PlaceOrderRequest request = buildRequest(3, new BigDecimal("20.00"));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Order result = orderService.placeOrder(request);

        assertThat(result.getTotalAmount()).isEqualByComparingTo("60.00");
    }

    @Test
    void placeOrder_shouldWriteOrderCreatedOutboxEvent() {
        PlaceOrderRequest request = buildRequest(1, new BigDecimal("99.99"));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orderService.placeOrder(request);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());

        OutboxEvent outbox = captor.getValue();
        assertThat(outbox.getEventType()).isEqualTo(EventType.ORDER_CREATED.name());
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getPayload()).contains("ORDER_CREATED");
    }

    @Test
    void placeOrder_shouldIncrementPlacedCounter() {
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orderService.placeOrder(buildRequest(1, new BigDecimal("10.00")));

        assertThat(meterRegistry.counter("pulsemart.orders.placed").count()).isEqualTo(1.0);
    }

    // ── markInventoryReserved ─────────────────────────────────────────────────

    @Test
    void markInventoryReserved_shouldUpdateStatusAndWritePaymentInitiatedOutbox() {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Order order = existingOrder(orderId, OrderStatus.PENDING);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orderService.markInventoryReserved(orderId, reservationId);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        assertThat(order.getReservationId()).isEqualTo(reservationId);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(EventType.PAYMENT_INITIATED.name());
    }

    // ── markCompleted ─────────────────────────────────────────────────────────

    @Test
    void markCompleted_shouldSetStatusToCompletedAndEmitOutbox() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Order order = existingOrder(orderId, OrderStatus.PAYMENT_PENDING);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orderService.markCompleted(orderId, paymentId);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(EventType.ORDER_COMPLETED.name());
        assertThat(meterRegistry.counter("pulsemart.orders.total", "status", "completed").count())
                .isEqualTo(1.0);
    }

    // ── markCancelled ─────────────────────────────────────────────────────────

    @Test
    void markCancelled_shouldSetStatusToCancelledAndEmitOutbox() {
        UUID orderId = UUID.randomUUID();
        Order order = existingOrder(orderId, OrderStatus.PENDING);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orderService.markCancelled(orderId, "Inventory insufficient");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(EventType.ORDER_CANCELLED.name());
        assertThat(captor.getValue().getPayload()).contains("Inventory insufficient");
        assertThat(meterRegistry.counter("pulsemart.orders.total", "status", "cancelled").count())
                .isEqualTo(1.0);
    }

    // ── getOrder ──────────────────────────────────────────────────────────────

    @Test
    void getOrder_shouldThrowWhenOrderNotFound() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(orderId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(orderId.toString());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private PlaceOrderRequest buildRequest(int quantity, BigDecimal unitPrice) {
        PlaceOrderRequest req = new PlaceOrderRequest();
        req.setCustomerId(UUID.randomUUID());

        PlaceOrderRequest.OrderItemRequest item = new PlaceOrderRequest.OrderItemRequest();
        item.setProductId(UUID.randomUUID());
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        req.setItems(List.of(item));
        return req;
    }

    private Order existingOrder(UUID orderId, OrderStatus status) {
        return Order.builder()
                .id(orderId)
                .customerId(UUID.randomUUID())
                .status(status)
                .totalAmount(new BigDecimal("99.99"))
                .build();
    }
}
