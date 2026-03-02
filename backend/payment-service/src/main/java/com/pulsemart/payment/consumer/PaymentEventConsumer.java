package com.pulsemart.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsemart.payment.repository.ProcessedEventRepository;
import com.pulsemart.payment.service.PaymentService;
import com.pulsemart.shared.event.EventEnvelope;
import com.pulsemart.shared.event.EventType;
import com.pulsemart.shared.event.payload.PaymentInitiatedPayload;
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
public class PaymentEventConsumer {

    private final PaymentService paymentService;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Consumes order.events:
     *  - PAYMENT_INITIATED → process payment (30% simulated failure)
     */
    @KafkaListener(topics = "order.events", groupId = "payment-service")
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

            if (EventType.PAYMENT_INITIATED.name().equals(envelope.getEventType())) {
                PaymentInitiatedPayload payload = objectMapper.convertValue(
                        envelope.getPayload(), PaymentInitiatedPayload.class);
                paymentService.processPayment(payload);
            }
        } catch (Exception e) {
            log.error("Failed to process order event from partition={} offset={}",
                    record.partition(), record.offset(), e);
            throw new RuntimeException(e);
        }
    }
}
