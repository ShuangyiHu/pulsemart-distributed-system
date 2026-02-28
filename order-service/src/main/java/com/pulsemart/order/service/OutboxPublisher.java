package com.pulsemart.order.service;

import com.pulsemart.order.domain.OutboxEvent;
import com.pulsemart.order.domain.OutboxStatus;
import com.pulsemart.order.repository.OutboxRepository;
import com.pulsemart.shared.event.EventType;
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

    /** Routes each eventType to its Kafka topic. */
    private static final Map<String, String> TOPIC_MAP = Map.of(
            EventType.ORDER_CREATED.name(),     "order.events",
            EventType.PAYMENT_INITIATED.name(), "order.events",
            EventType.ORDER_CANCELLED.name(),   "order.events",
            EventType.ORDER_COMPLETED.name(),   "order.events"
    );

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    /**
     * Register the outbox pending gauge once at startup with a supplier.
     * Micrometer holds a strong reference to the lambda (not to a transient Number),
     * so the gauge correctly reflects the live DB count on every scrape.
     */
    @PostConstruct
    void registerMetrics() {
        Gauge.builder("pulsemart.outbox.pending_events",
                        outboxRepository,
                        repo -> repo.countByStatus(OutboxStatus.PENDING))
                .tag("service", "order-service")
                .description("Number of outbox events waiting to be published")
                .strongReference(true)
                .register(meterRegistry);
    }

    /**
     * Polls every second for PENDING outbox rows.
     * FOR UPDATE SKIP LOCKED prevents duplicate publishing when
     * multiple instances of this service run concurrently.
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository.findPendingEventsForUpdate();
        if (events.isEmpty()) return;

        log.debug("OutboxPublisher: processing {} pending events", events.size());

        for (OutboxEvent event : events) {
            String topic = TOPIC_MAP.getOrDefault(event.getEventType(), "order.events");
            try {
                // Synchronous send — only mark SENT after Kafka ACKs
                kafkaTemplate.send(topic, event.getAggregateId().toString(), event.getPayload())
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);

                event.setStatus(OutboxStatus.SENT);
                event.setSentAt(Instant.now());
                log.info("Outbox published: eventType={} eventId={} topic={}",
                        event.getEventType(), event.getEventId(), topic);

            } catch (Exception ex) {
                int retries = event.getRetryCount() + 1;
                event.setRetryCount(retries);
                if (retries >= MAX_RETRIES) {
                    event.setStatus(OutboxStatus.FAILED);
                    meterRegistry.counter("pulsemart.outbox.failed",
                            "eventType", event.getEventType()).increment();
                    log.error("Outbox event permanently failed after {} retries: eventId={}",
                            MAX_RETRIES, event.getEventId(), ex);
                } else {
                    log.warn("Outbox publish failed (attempt {}/{}): eventId={}",
                            retries, MAX_RETRIES, event.getEventId(), ex);
                }
            }
            outboxRepository.save(event);
        }
    }
}
