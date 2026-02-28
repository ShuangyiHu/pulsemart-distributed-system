package com.pulsemart.inventory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsemart.inventory.domain.*;
import com.pulsemart.inventory.repository.OutboxRepository;
import com.pulsemart.inventory.repository.ProductRepository;
import com.pulsemart.inventory.repository.ReservationRepository;
import com.pulsemart.shared.event.EventEnvelope;
import com.pulsemart.shared.event.EventType;
import com.pulsemart.shared.event.payload.InventoryFailedPayload;
import com.pulsemart.shared.event.payload.InventoryReleasedPayload;
import com.pulsemart.shared.event.payload.InventoryReservedPayload;
import com.pulsemart.shared.event.payload.OrderCreatedPayload;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final ProductRepository productRepository;
    private final ReservationRepository reservationRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * Attempt to reserve stock for each item in the order.
     * If any product has insufficient stock, roll back and emit INVENTORY_FAILED.
     * All DB writes (product updates, reservations, outbox) share a single transaction.
     */
    @Transactional
    public void reserveStock(UUID orderId, List<OrderCreatedPayload.OrderItemPayload> items) {
        for (OrderCreatedPayload.OrderItemPayload item : items) {
            Product product = productRepository.findByIdForUpdate(item.getProductId())
                    .orElse(null);

            int available = product == null ? 0 : product.getStockQuantity();
            if (product == null || available < item.getQuantity()) {
                String reason = product == null
                        ? "Product not found: " + item.getProductId()
                        : "Insufficient stock for product " + item.getProductId()
                          + " (available=" + available + ", requested=" + item.getQuantity() + ")";
                log.warn("Inventory insufficient for orderId={}: {}", orderId, reason);

                saveOutboxEvent(EventType.INVENTORY_FAILED.name(), orderId,
                        InventoryFailedPayload.builder()
                                .orderId(orderId)
                                .productId(item.getProductId())
                                .requestedQuantity(item.getQuantity())
                                .availableQuantity(available)
                                .reason(reason)
                                .build());

                meterRegistry.counter("pulsemart.inventory.reservations", "outcome", "failed").increment();
                return; // outbox row will be committed, reservation aborted
            }

            product.setStockQuantity(product.getStockQuantity() - item.getQuantity());
            productRepository.save(product);

            reservationRepository.save(Reservation.builder()
                    .orderId(orderId)
                    .productId(item.getProductId())
                    .quantity(item.getQuantity())
                    .status(ReservationStatus.RESERVED)
                    .build());
        }

        // All items reserved — emit one INVENTORY_RESERVED per order
        UUID reservationId = UUID.randomUUID();
        OrderCreatedPayload.OrderItemPayload first = items.get(0);
        saveOutboxEvent(EventType.INVENTORY_RESERVED.name(), orderId,
                InventoryReservedPayload.builder()
                        .orderId(orderId)
                        .reservationId(reservationId)
                        .productId(first.getProductId())
                        .quantity(first.getQuantity())
                        .build());

        log.info("Inventory reserved for orderId={} reservationId={}", orderId, reservationId);
        meterRegistry.counter("pulsemart.inventory.reservations", "outcome", "reserved").increment();
    }

    /**
     * Release all RESERVED reservations for the given order (compensation step).
     * Restores stock_quantity and emits INVENTORY_RELEASED.
     */
    @Transactional
    public void releaseStock(UUID orderId) {
        List<Reservation> reserved = reservationRepository
                .findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED);

        if (reserved.isEmpty()) {
            log.warn("No RESERVED reservations found for orderId={} — nothing to release", orderId);
            return;
        }

        for (Reservation r : reserved) {
            productRepository.findByIdForUpdate(r.getProductId()).ifPresent(product -> {
                product.setStockQuantity(product.getStockQuantity() + r.getQuantity());
                productRepository.save(product);
            });
            r.setStatus(ReservationStatus.RELEASED);
            reservationRepository.save(r);
        }

        Reservation first = reserved.get(0);
        saveOutboxEvent(EventType.INVENTORY_RELEASED.name(), orderId,
                InventoryReleasedPayload.builder()
                        .orderId(orderId)
                        .productId(first.getProductId())
                        .quantity(first.getQuantity())
                        .reason("Payment failed or order cancelled")
                        .build());

        log.info("Inventory released for orderId={}", orderId);
        meterRegistry.counter("pulsemart.inventory.reservations", "outcome", "released").increment();
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
                                    .sourceService("inventory-service")
                                    .version("1")
                                    .payload(payload)
                                    .build()))
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox payload for " + eventType, e);
        }
    }
}
