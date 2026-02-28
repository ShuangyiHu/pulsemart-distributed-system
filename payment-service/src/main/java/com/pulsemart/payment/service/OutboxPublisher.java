package com.pulsemart.payment.service;

import com.pulsemart.payment.domain.OutboxEvent;
import com.pulsemart.payment.domain.OutboxStatus;
import com.pulsemart.payment.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private static final int MAX_RETRIES = 5;

    private static final Map<String, String> TOPIC_MAP = Map.of(
            "PAYMENT_SUCCEEDED", "payment.events",
            "PAYMENT_FAILED",    "payment.events"
    );

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    @PostConstruct
    void registerMetrics() {
        Gauge.builder("pulsemart.outbox.pending_events", outboxRepository,
                        repo -> repo.countByStatus(OutboxStatus.PENDING))
                .tag("service", "payment-service")
                .strongReference(true)
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository.findPendingEventsForUpdate();
        if (events.isEmpty()) return;

        for (OutboxEvent event : events) {
            String topic = TOPIC_MAP.getOrDefault(event.getEventType(), "payment.events");
            try {
                kafkaTemplate.send(topic, event.getEventId().toString(), event.getPayload())
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
                event.setStatus(OutboxStatus.SENT);
                event.setSentAt(Instant.now());
                log.debug("Outbox event published: eventId={} type={}", event.getEventId(), event.getEventType());
            } catch (Exception e) {
                int retries = event.getRetryCount() + 1;
                event.setRetryCount(retries);
                if (retries >= MAX_RETRIES) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error("Outbox event FAILED after {} retries: eventId={}", retries, event.getEventId(), e);
                } else {
                    log.warn("Outbox publish failed (attempt {}): eventId={}", retries, event.getEventId(), e);
                }
            }
            outboxRepository.save(event);
        }
    }
}
