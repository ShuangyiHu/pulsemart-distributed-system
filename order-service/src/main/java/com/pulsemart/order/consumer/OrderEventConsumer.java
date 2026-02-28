package com.pulsemart.order.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsemart.order.repository.ProcessedEventRepository;
import com.pulsemart.order.service.OrderService;
import com.pulsemart.shared.event.EventEnvelope;
import com.pulsemart.shared.event.EventType;
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
            // TODO Phase 5: extract payload and call orderService.markInventoryReserved(...)
        } else if (EventType.INVENTORY_FAILED.name().equals(type)) {
            // TODO Phase 5: extract payload and call orderService.markCancelled(...)
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
            // TODO Phase 5: extract payload and call orderService.markCompleted(...)
        } else if (EventType.PAYMENT_FAILED.name().equals(type)) {
            // TODO Phase 5: extract payload and call orderService.markCancelled(...)
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
