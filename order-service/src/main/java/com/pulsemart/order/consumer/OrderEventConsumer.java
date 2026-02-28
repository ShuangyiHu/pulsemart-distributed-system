package com.pulsemart.order.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsemart.order.repository.ProcessedEventRepository;
import com.pulsemart.order.service.OrderService;
import com.pulsemart.shared.event.EventEnvelope;
import com.pulsemart.shared.event.EventType;
import com.pulsemart.shared.event.payload.InventoryFailedPayload;
import com.pulsemart.shared.event.payload.InventoryReservedPayload;
import com.pulsemart.shared.event.payload.PaymentFailedPayload;
import com.pulsemart.shared.event.payload.PaymentSucceededPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventConsumer {

    private final OrderService orderService;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "inventory.events",
            groupId = "order-service-inventory",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onInventoryEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = deserialize(record.value());
        if (envelope == null) return;
        if (!markProcessed(envelope.getEventId())) return;

        String type = envelope.getEventType();
        log.info("OrderEventConsumer received: eventType={} eventId={}", type, envelope.getEventId());

        if (EventType.INVENTORY_RESERVED.name().equals(type)) {
            InventoryReservedPayload payload = objectMapper.convertValue(
                    envelope.getPayload(), InventoryReservedPayload.class);
            orderService.markInventoryReserved(payload.getOrderId(), payload.getReservationId());

        } else if (EventType.INVENTORY_FAILED.name().equals(type)) {
            InventoryFailedPayload payload = objectMapper.convertValue(
                    envelope.getPayload(), InventoryFailedPayload.class);
            orderService.markCancelled(payload.getOrderId(), payload.getReason());
        }
    }

    @KafkaListener(
            topics = "payment.events",
            groupId = "order-service-payment",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = deserialize(record.value());
        if (envelope == null) return;
        if (!markProcessed(envelope.getEventId())) return;

        String type = envelope.getEventType();
        log.info("OrderEventConsumer received: eventType={} eventId={}", type, envelope.getEventId());

        if (EventType.PAYMENT_SUCCEEDED.name().equals(type)) {
            PaymentSucceededPayload payload = objectMapper.convertValue(
                    envelope.getPayload(), PaymentSucceededPayload.class);
            orderService.markCompleted(payload.getOrderId(), payload.getPaymentId());

        } else if (EventType.PAYMENT_FAILED.name().equals(type)) {
            PaymentFailedPayload payload = objectMapper.convertValue(
                    envelope.getPayload(), PaymentFailedPayload.class);
            orderService.markCancelled(payload.getOrderId(), payload.getFailureReason());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Idempotency guard: inserts into processed_events using ON CONFLICT DO NOTHING.
     * Returns true if this is the first time we see this eventId (proceed with processing).
     * Returns false if the event was already processed (skip).
     */
    private boolean markProcessed(UUID eventId) {
        int inserted = processedEventRepository.insertIfAbsent(eventId);
        if (inserted == 0) {
            log.info("Duplicate event skipped: eventId={}", eventId);
            return false;
        }
        return true;
    }

    private EventEnvelope deserialize(String json) {
        try {
            return objectMapper.readValue(json, EventEnvelope.class);
        } catch (Exception e) {
            log.error("Failed to deserialize event envelope: {}", json, e);
            return null;
        }
    }
}
