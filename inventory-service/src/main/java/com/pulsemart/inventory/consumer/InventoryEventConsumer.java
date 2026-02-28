package com.pulsemart.inventory.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsemart.inventory.repository.ProcessedEventRepository;
import com.pulsemart.inventory.service.InventoryService;
import com.pulsemart.shared.event.EventEnvelope;
import com.pulsemart.shared.event.EventType;
import com.pulsemart.shared.event.payload.OrderCancelledPayload;
import com.pulsemart.shared.event.payload.OrderCreatedPayload;
import com.pulsemart.shared.event.payload.PaymentFailedPayload;
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
public class InventoryEventConsumer {

    private final InventoryService inventoryService;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Consumes order.events:
     *  - ORDER_CREATED  → reserve stock
     *  - ORDER_CANCELLED → release stock (compensation triggered by order service)
     */
    @KafkaListener(topics = "order.events", groupId = "inventory-service")
    @Transactional
    public void consumeOrderEvents(ConsumerRecord<String, String> record) {
        try {
            EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
            UUID eventId = envelope.getEventId();

            // Idempotency guard — skip already-processed events
            if (processedEventRepository.insertIfAbsent(eventId) == 0) {
                log.info("Duplicate event skipped: eventId={} type={}", eventId, envelope.getEventType());
                return;
            }

            log.info("Processing event: eventId={} type={}", eventId, envelope.getEventType());

            if (EventType.ORDER_CREATED.name().equals(envelope.getEventType())) {
                OrderCreatedPayload payload = objectMapper.convertValue(
                        envelope.getPayload(), OrderCreatedPayload.class);
                inventoryService.reserveStock(payload.getOrderId(), payload.getItems());

            } else if (EventType.ORDER_CANCELLED.name().equals(envelope.getEventType())) {
                OrderCancelledPayload payload = objectMapper.convertValue(
                        envelope.getPayload(), OrderCancelledPayload.class);
                if (payload.getReservationId() != null) {
                    inventoryService.releaseStock(payload.getOrderId());
                }
            }
        } catch (Exception e) {
            log.error("Failed to process order event from partition={} offset={}",
                    record.partition(), record.offset(), e);
            throw new RuntimeException(e); // re-throw so Kafka retries / sends to DLT
        }
    }

    /**
     * Consumes payment.events:
     *  - PAYMENT_FAILED → release stock (compensation)
     */
    @KafkaListener(topics = "payment.events", groupId = "inventory-service")
    @Transactional
    public void consumePaymentEvents(ConsumerRecord<String, String> record) {
        try {
            EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
            UUID eventId = envelope.getEventId();

            if (processedEventRepository.insertIfAbsent(eventId) == 0) {
                log.info("Duplicate event skipped: eventId={} type={}", eventId, envelope.getEventType());
                return;
            }

            log.info("Processing event: eventId={} type={}", eventId, envelope.getEventType());

            if (EventType.PAYMENT_FAILED.name().equals(envelope.getEventType())) {
                PaymentFailedPayload payload = objectMapper.convertValue(
                        envelope.getPayload(), PaymentFailedPayload.class);
                inventoryService.releaseStock(payload.getOrderId());
            }
        } catch (Exception e) {
            log.error("Failed to process payment event from partition={} offset={}",
                    record.partition(), record.offset(), e);
            throw new RuntimeException(e);
        }
    }
}
