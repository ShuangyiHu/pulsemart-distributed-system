package com.pulsemart.summarizer.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsemart.shared.event.EventEnvelope;
import com.pulsemart.shared.event.EventType;
import com.pulsemart.summarizer.repository.ProcessedEventRepository;
import com.pulsemart.summarizer.service.SummarizerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class SummarizerEventConsumer {

    private final SummarizerService summarizerService;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    private final Map<UUID, List<EventEnvelope>> sagaEvents = new ConcurrentHashMap<>();

    private static final Set<String> TERMINAL_EVENTS = Set.of(
            EventType.ORDER_COMPLETED.name(),
            EventType.ORDER_CANCELLED.name()
    );

    @KafkaListener(
            topics = {"order.events", "inventory.events", "payment.events"},
            groupId = "ai-summarizer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = deserialize(record.value());
        if (envelope == null) return;

        UUID orderId = extractOrderId(envelope);
        if (orderId == null) {
            log.warn("Could not extract orderId from event: eventType={}", envelope.getEventType());
            return;
        }

        sagaEvents.computeIfAbsent(orderId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(envelope);

        log.debug("Collected event for orderId={}: eventType={}", orderId, envelope.getEventType());

        if (TERMINAL_EVENTS.contains(envelope.getEventType())) {
            if (!markProcessed(envelope.getEventId())) return;

            log.info("Terminal event received for orderId={}: {}", orderId, envelope.getEventType());
            List<EventEnvelope> events = sagaEvents.remove(orderId);
            if (events != null) {
                try {
                    summarizerService.summarizeOrder(orderId, envelope.getEventType(), events);
                } catch (Exception e) {
                    log.error("Failed to generate summary for orderId={}: {}", orderId, e.getMessage(), e);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private UUID extractOrderId(EventEnvelope envelope) {
        try {
            Map<String, Object> payload = objectMapper.convertValue(envelope.getPayload(), Map.class);
            Object orderIdObj = payload.get("orderId");
            if (orderIdObj instanceof String s) {
                return UUID.fromString(s);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to extract orderId from payload: {}", e.getMessage());
            return null;
        }
    }

    private boolean markProcessed(UUID eventId) {
        int inserted = processedEventRepository.insertIfAbsent(eventId);
        if (inserted == 0) {
            log.info("Duplicate terminal event skipped: eventId={}", eventId);
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
