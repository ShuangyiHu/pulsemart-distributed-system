package com.pulsemart.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulsemart.inventory.domain.*;
import com.pulsemart.inventory.repository.OutboxRepository;
import com.pulsemart.inventory.repository.ProductRepository;
import com.pulsemart.inventory.repository.ReservationRepository;
import com.pulsemart.shared.event.EventType;
import com.pulsemart.shared.event.payload.OrderCreatedPayload;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private OutboxRepository outboxRepository;

    private ObjectMapper objectMapper;
    private MeterRegistry meterRegistry;
    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        meterRegistry = new SimpleMeterRegistry();
        inventoryService = new InventoryService(
                productRepository, reservationRepository, outboxRepository,
                objectMapper, meterRegistry);
    }

    // ── reserveStock — happy path ─────────────────────────────────────────────

    @Test
    void reserveStock_shouldDecrementStockAndCreateReservation() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Product product = product(productId, 10);
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.reserveStock(orderId, List.of(item(productId, 3)));

        assertThat(product.getStockQuantity()).isEqualTo(7);
        verify(productRepository).save(product);
        verify(reservationRepository).save(argThat(r ->
                r.getOrderId().equals(orderId)
                        && r.getProductId().equals(productId)
                        && r.getQuantity() == 3
                        && r.getStatus() == ReservationStatus.RESERVED));
    }

    @Test
    void reserveStock_shouldEmitInventoryReservedOutboxEvent() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product(productId, 10)));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.reserveStock(orderId, List.of(item(productId, 2)));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent outbox = captor.getValue();
        assertThat(outbox.getEventType()).isEqualTo(EventType.INVENTORY_RESERVED.name());
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getPayload()).contains("INVENTORY_RESERVED");
    }

    @Test
    void reserveStock_shouldIncrementReservedCounter() {
        UUID productId = UUID.randomUUID();
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product(productId, 5)));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.reserveStock(UUID.randomUUID(), List.of(item(productId, 1)));

        assertThat(meterRegistry.counter("pulsemart.inventory.reservations", "outcome", "reserved").count())
                .isEqualTo(1.0);
    }

    // ── reserveStock — insufficient stock ─────────────────────────────────────

    @Test
    void reserveStock_shouldEmitInventoryFailedWhenInsufficientStock() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product(productId, 1)));

        inventoryService.reserveStock(orderId, List.of(item(productId, 5)));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(EventType.INVENTORY_FAILED.name());
        assertThat(captor.getValue().getPayload()).contains("Insufficient stock");

        // Stock must NOT have been decremented
        verify(productRepository, never()).save(any());
    }

    @Test
    void reserveStock_shouldEmitInventoryFailedWhenProductNotFound() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.empty());

        inventoryService.reserveStock(orderId, List.of(item(productId, 1)));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(EventType.INVENTORY_FAILED.name());
        assertThat(captor.getValue().getPayload()).contains("Product not found");
    }

    @Test
    void reserveStock_shouldIncrementFailedCounter() {
        UUID productId = UUID.randomUUID();
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product(productId, 0)));

        inventoryService.reserveStock(UUID.randomUUID(), List.of(item(productId, 1)));

        assertThat(meterRegistry.counter("pulsemart.inventory.reservations", "outcome", "failed").count())
                .isEqualTo(1.0);
    }

    // ── releaseStock ──────────────────────────────────────────────────────────

    @Test
    void releaseStock_shouldRestoreStockAndMarkReservationReleased() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Product product = product(productId, 5);
        Reservation reservation = reservation(orderId, productId, 3);

        when(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED))
                .thenReturn(List.of(reservation));
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.releaseStock(orderId);

        assertThat(product.getStockQuantity()).isEqualTo(8); // 5 + 3
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        verify(reservationRepository).save(reservation);
    }

    @Test
    void releaseStock_shouldEmitInventoryReleasedOutboxEvent() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED))
                .thenReturn(List.of(reservation(orderId, productId, 2)));
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product(productId, 0)));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.releaseStock(orderId);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(EventType.INVENTORY_RELEASED.name());
        assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void releaseStock_shouldDoNothingWhenNoReservationsFound() {
        UUID orderId = UUID.randomUUID();
        when(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED))
                .thenReturn(List.of());

        inventoryService.releaseStock(orderId);

        verify(outboxRepository, never()).save(any());
        verify(productRepository, never()).findByIdForUpdate(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Product product(UUID id, int stock) {
        return Product.builder().id(id).name("Test Product").stockQuantity(stock).build();
    }

    private OrderCreatedPayload.OrderItemPayload item(UUID productId, int quantity) {
        return OrderCreatedPayload.OrderItemPayload.builder()
                .productId(productId)
                .quantity(quantity)
                .unitPrice(new BigDecimal("9.99"))
                .build();
    }

    private Reservation reservation(UUID orderId, UUID productId, int quantity) {
        return Reservation.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .status(ReservationStatus.RESERVED)
                .build();
    }
}
